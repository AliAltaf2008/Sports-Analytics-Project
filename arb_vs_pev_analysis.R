

options(repos = c(CRAN = "https://cloud.r-project.org"))

# ---- graphics / packages ----
if (!requireNamespace("ragg", quietly = TRUE)) install.packages("ragg", dependencies = TRUE)
if (requireNamespace("ragg", quietly = TRUE)) {
  Sys.setenv(R_GRAPHICS_DEVICE = "ragg_png")
} else {
  options(bitmapType = "cairo")
}

pkgs <- c("readr","dplyr","tidyr","ggplot2","lubridate","stringr","purrr","scales","broom")
need <- pkgs[!vapply(pkgs, requireNamespace, FUN.VALUE = logical(1), quietly = TRUE)]
if (length(need)) install.packages(need, dependencies = TRUE)

suppressPackageStartupMessages({
  library(readr); library(dplyr); library(tidyr); library(ggplot2)
  library(lubridate); library(stringr); library(purrr); library(scales); library(broom)
})


paths <- list(
  arb_ml  = "arbitrageML.csv",
  arb_sp  = "arbitrageSpread.csv",
  arb_tot = "arbitrageTotals.csv",
  pev_ml  = "positiveEVML.csv",
  pev_sp  = "positiveEVSpread.csv",
  pev_tot = "positiveEVTotals.csv"
)
out_dir <- "analysis_outputs"
if (!dir.exists(out_dir)) dir.create(out_dir, recursive = TRUE)

balance_mode <- "per_day"   # one of: "none", "overall", "per_day"
dedupe <- TRUE              # drop clear duplicate rows
cap_weight <- 10            # guard against extreme upweighting
set.seed(42)                # reproducibility for bootstraps


read_one <- function(fp) {
  if (!file.exists(fp)) stop(paste("Missing file:", fp))
  df <- readr::read_csv(fp, show_col_types = FALSE)

  # Parse date
  if ("date" %in% names(df)) {
    dt <- suppressWarnings(ymd_hms(df$date, quiet = TRUE))
    if (all(is.na(dt))) dt <- suppressWarnings(ymd(df$date, quiet = TRUE))
    df$date <- dt
  }

  # Coerce success -> logical
  if ("success" %in% names(df)) {
    if (!is.logical(df$success)) {
      if (is.numeric(df$success)) df$success <- df$success > 0.5
      if (is.character(df$success)) df$success <- tolower(df$success) %in% c("true","t","1","win","w","yes","y")
    }
  }

  # Numeric columns
  num_cols <- intersect(c("oddsUsed","sharpOdds","profit","ev"), names(df))
  df[num_cols] <- lapply(df[num_cols], function(x) suppressWarnings(as.numeric(x)))

  # Normalize betType
  if ("betType" %in% names(df)) {
    df$betType <- str_to_title(as.character(df$betType))
    df$betType <- dplyr::recode(df$betType,
                         "Moneyline"="Moneyline","Spread"="Spread","Totals"="Totals",
                         .default=df$betType)
  }

  df
}

safe_read <- purrr::safely(read_one)

w_mean <- function(x, w) {
  ok <- is.finite(x) & is.finite(w) & w > 0
  if (!any(ok)) return(NA_real_)
  sum(w[ok] * x[ok]) / sum(w[ok])
}

max_drawdown <- function(equity) {
  # Returns max drawdown as a fraction (negative number) and index of min
  peak <- cummax(equity)
  dd <- equity - peak
  which_min <- which.min(dd)
  if (length(which_min) == 0) return(list(value = 0, idx = NA_integer_))
  list(value = ifelse(max(peak) == 0, 0, min(dd/peak)), idx = which_min)
}

mean_ci <- function(x, conf = 0.95) {
  x <- x[is.finite(x)]
  n <- length(x); if (n < 2) return(c(NA, NA, NA))
  m <- mean(x); s <- sd(x)
  err <- qt( (1+conf)/2, df = n-1) * s / sqrt(n)
  c(m - err, m, m + err)
}

binom_ci <- function(k, n, conf = 0.95) {
  # Wilson score interval
  if (n == 0) return(c(NA, NA, NA))
  p <- k/n
  z <- qnorm((1+conf)/2)
  denom <- 1 + z^2/n
  centre <- (p + z^2/(2*n)) / denom
  adj <- (z * sqrt( (p*(1-p) + z^2/(4*n)) / n )) / denom
  c(centre - adj, centre, centre + adj)
}


dfs <- purrr::imap(paths, ~{
  res <- safe_read(.x)
  if (!is.null(res$error)) stop(res$error)
  df <- res$result
  df$source_key <- .y
  df
})

data <- dplyr::bind_rows(dfs) %>%
  mutate(
    strategy = dplyr::case_when(
      stringr::str_detect(source_key, "^arb_") ~ "Arbitrage",
      strategy %in% c("Arbitrage","ARBITRAGE") ~ "Arbitrage",
      TRUE ~ "Positive EV"
    ),
    betType  = dplyr::case_when(
      !is.na(betType) & betType != "" ~ betType,
      stringr::str_detect(source_key, "ml$") ~ "Moneyline",
      stringr::str_detect(source_key, "sp$") ~ "Spread",
      stringr::str_detect(source_key, "tot$") ~ "Totals",
      TRUE ~ "Unknown"
    ),
    date = lubridate::as_datetime(date),
    roi  = profit # per-bet profit already normalized by your Java sim
  ) %>%
  dplyr::filter(!is.na(date), is.finite(roi))

if (dedupe) {
  before <- nrow(data)
  data <- data %>%
    dplyr::distinct(gameId, date, strategy, betType, pickTeamId, oddsUsed, sharpOdds, ev, roi, .keep_all = TRUE)
  after <- nrow(data)
  message(sprintf("[dedupe] %d -> %d rows (removed %d)", before, after, before - after))
}


data <- data %>% mutate(w = 1.0)

if (balance_mode == "overall") {
  n_by_strat <- data %>% count(strategy, name = "n")
  min_n <- min(n_by_strat$n)
  w_tbl <- n_by_strat %>% mutate(w_bal = min_n / pmax(n,1))
  data <- data %>% left_join(w_tbl, by = "strategy") %>%
    mutate(w = if_else(is.na(w_bal), 1.0, w_bal)) %>% select(-w_bal)
} else if (balance_mode == "per_day") {
  data <- data %>% mutate(date_day = as_date(date))
  day_counts <- data %>% count(date_day, strategy, name = "n")
  ref_counts <- day_counts %>% group_by(date_day) %>% summarise(n_ref = min(n), .groups = "drop")
  w_day <- day_counts %>% left_join(ref_counts, by = "date_day") %>%
    mutate(w_bal = if_else(n > 0, n_ref / n, 0.0))
  data <- data %>% left_join(w_day %>% select(date_day, strategy, w_bal),
                             by = c("date_day","strategy")) %>%
    mutate(w = if_else(is.na(w_bal), 1.0, w_bal)) %>% select(-w_bal)
}

data <- data %>% mutate(w = pmin(w, cap_weight))


sum_overall <- data %>%
  group_by(strategy) %>%
  summarise(
    n_bets = n(),
    win_rate = w_mean(as.numeric(success), w),
    avg_roi  = w_mean(roi, w),
    sd_roi   = sqrt(w_mean((roi - w_mean(roi,w))^2, w)),
    sharpe_like = ifelse(sd_roi > 0, avg_roi/sd_roi, NA_real_),
    avg_ev   = w_mean(ev, w),
    eff_bet_count = sum(w),
    .groups = "drop"
  )

sum_by_type <- data %>%
  group_by(strategy, betType) %>%
  summarise(
    n_bets = n(),
    win_rate = w_mean(as.numeric(success), w),
    avg_roi  = w_mean(roi, w),
    sd_roi   = sqrt(w_mean((roi - w_mean(roi,w))^2, w)),
    sharpe_like = ifelse(sd_roi > 0, avg_roi/sd_roi, NA_real_),
    avg_ev   = w_mean(ev, w),
    eff_bet_count = sum(w),
    .groups = "drop"
  ) %>% arrange(strategy, betType)

# Confidence intervals (unweighted simple CIs for readability)
ci_overall <- data %>%
  group_by(strategy) %>%
  summarise(
    n_bets = n(),
    win_rate_ci = list(binom_ci(sum(success, na.rm=TRUE), n_bets)),
    roi_ci      = list(mean_ci(roi)),
    .groups = "drop"
  ) %>%
  mutate(
    win_lo = purrr::map_dbl(win_rate_ci, 1),
    win_md = purrr::map_dbl(win_rate_ci, 2),
    win_hi = purrr::map_dbl(win_rate_ci, 3),
    roi_lo = purrr::map_dbl(roi_ci, 1),
    roi_md = purrr::map_dbl(roi_ci, 2),
    roi_hi = purrr::map_dbl(roi_ci, 3)
  ) %>%
  select(-win_rate_ci, -roi_ci)


pev <- data %>% filter(strategy=="Positive EV", is.finite(ev), is.finite(roi))
cor_ev_profit <- if (nrow(pev) > 3) cor(pev$ev, pev$roi, use="complete.obs") else NA_real_

pevc <- tibble()
if (nrow(pev) > 0) {
  q <- quantile(pev$ev, probs=seq(0,1,by=0.1), na.rm=TRUE, type=7)
  # ensure unique breaks to avoid empty/NA labels
  brks <- unique(q)
  if (length(brks) < 3) {
    # fallback: fixed small buckets
    brks <- c(min(pev$ev, na.rm=TRUE)-1e-9, 0, max(pev$ev, na.rm=TRUE)+1e-9)
  }
  pev$ev_decile <- cut(pev$ev, breaks=brks, include.lowest=TRUE, labels=FALSE)
  pevc <- pev %>%
    filter(!is.na(ev_decile)) %>%
    group_by(ev_decile) %>%
    summarise(
      n = n(),
      eff_n = sum(w),
      mean_ev = w_mean(ev, w),
      win_rate = w_mean(as.numeric(success), w),
      avg_roi = w_mean(roi, w),
      .groups = "drop"
    )
}


ts <- data %>%
  mutate(date_day = as_date(date)) %>%
  group_by(strategy, date_day) %>%
  summarise(daily_profit = sum(roi*w, na.rm=TRUE), .groups="drop") %>%
  group_by(strategy) %>%
  arrange(date_day, .by_group=TRUE) %>%
  mutate(cum_profit = cumsum(daily_profit))

dd_tbl <- ts %>% group_by(strategy) %>%
  summarise(
    max_dd = {
      dd <- max_drawdown(cum_profit)
      dd$value
    },
    .groups = "drop"
  )


if (!dir.exists(out_dir)) dir.create(out_dir, recursive = TRUE)
write_csv(sum_overall, file.path(out_dir, "summary_overall_weighted.csv"))
write_csv(sum_by_type,  file.path(out_dir, "summary_by_type_weighted.csv"))
write_csv(ci_overall,   file.path(out_dir, "summary_overall_confidence_intervals.csv"))
if (nrow(pevc)) write_csv(pevc, file.path(out_dir, "positiveEV_calibration_by_decile_weighted.csv"))
write_csv(dd_tbl,       file.path(out_dir, "max_drawdown_by_strategy.csv"))


# 1) ROI density
p_dist <- ggplot(data, aes(x=roi, weight=w, color=strategy, fill=strategy)) +
  geom_density(alpha=0.25, adjust=1.1) +
  coord_cartesian(xlim=quantile(data$roi, c(0.01,0.99), na.rm=TRUE)) +
  labs(title=paste0("ROI Density (",balance_mode," balance)"),
       x="Per-bet Profit (ROI units)", y="Weighted density") +
  theme_minimal()
ggsave(file.path(out_dir, "plot_roi_distribution_weighted.png"), p_dist, width=8, height=4.5, dpi=160)

# 2) Cumulative profit
p_cum <- ggplot(ts, aes(x=date_day, y=cum_profit, color=strategy)) +
  geom_line(linewidth=0.9) +
  labs(title=paste0("Cumulative Profit (",balance_mode," balance)"),
       x="Date", y="Cumulative Profit (weighted)") +
  theme_minimal()
ggsave(file.path(out_dir, "plot_cumulative_profit_weighted.png"), p_cum, width=9, height=5, dpi=160)

# 3) Calibration (Positive EV)
if (nrow(pevc)) {
  p_cal <- ggplot(pevc, aes(x=ev_decile, y=avg_roi, group=1)) +
    geom_line() + geom_point(size=2) +
    labs(title=paste0("Positive EV: Avg ROI by EV Decile (",balance_mode,")"),
         x="EV Decile (higher = larger model EV)", y="Avg ROI") +
    theme_minimal()
  ggsave(file.path(out_dir, "plot_positiveEV_calibration_by_decile.png"), p_cal, width=8, height=4, dpi=160)
}

# 4) Bar chart: Overall ROI with CI
roi_bars <- ci_overall %>%
  mutate(strategy = factor(strategy, levels = c("Arbitrage","Positive EV")))

p_bars <- ggplot(roi_bars, aes(x=strategy, y=roi_md)) +
  geom_col(width=0.6) +
  geom_errorbar(aes(ymin=roi_lo, ymax=roi_hi), width=0.2) +
  labs(title="Overall ROI (per-bet profit) with 95% CI",
       x=NULL, y="Per-bet Profit") +
  theme_minimal()
ggsave(file.path(out_dir, "plot_overall_roi_with_ci.png"), p_bars, width=6, height=4, dpi=160)


cat("\n=== DIGEST (balance_mode =", balance_mode, ") ===\n")
print(sum_overall)
cat("\n-- by bet type --\n"); print(sum_by_type)
cat("\nConfidence intervals (simple):\n"); print(ci_overall)
cat("\nMax Drawdown by strategy:\n"); print(dd_tbl)
cat("\nCorrelation (EV vs ROI) for Positive EV bets:\n"); print(cor_ev_profit)
cat("\nOutputs written to:", normalizePath(out_dir), "\n")
