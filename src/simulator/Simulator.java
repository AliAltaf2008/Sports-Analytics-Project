package simulator;

import java.util.*;
import model.GameDataB;
import model.SimulatedResult;
import util.Arbitrage;
import util.CsvWriter;
import util.PositiveEV;

public class Simulator {

    private static final double STAKE_FRACTION = 1.0;

    //MoneyLine
    public void simulatePositiveEV_ML(String moneylineJsonPath, String allGamesJsonPath, String outputCsvPath) {
        List<GameDataB> oddsList    = GameDataB.loadAll(moneylineJsonPath);
        List<GameDataB> outcomeList = GameDataB.loadAll(allGamesJsonPath);

        Map<String, List<GameDataB>> byGame = new HashMap<>();
        for (GameDataB o : oddsList) {
            if (o == null || o.getGameId() == null) continue;
            byGame.computeIfAbsent(o.getGameId(), k -> new ArrayList<>()).add(o);
        }

        // Map for grading (date/winner)
        Map<String, GameDataB> outcomeBy = new HashMap<>();
        for (GameDataB g : outcomeList) if (g != null && g.getGameId() != null) outcomeBy.put(g.getGameId(), g);

        List<SimulatedResult> sims = new ArrayList<>();
        int games = 0, kept = 0;

        for (Map.Entry<String, List<GameDataB>> entry : byGame.entrySet()) {
            games++;
            String gameId = entry.getKey();
            List<GameDataB> books = entry.getValue();
            if (books.size() < 2) continue;

            GameDataB actual = outcomeBy.get(gameId);
            String date = (actual == null) ? null : actual.getDate();
            String actualWinnerId = (actual == null || actual.getWinner() == null) ? null : String.valueOf(actual.getWinner());

            // Prefer Pinnacle as sharp; else pick the pair (soft,sharp) by hold
            for (int i = 0; i < books.size(); i++) {
                for (int j = 0; j < books.size(); j++) {
                    if (i == j) continue;
                    GameDataB soft  = books.get(i);
                    GameDataB sharp = books.get(j);

                    if (!isRowCompleteForML(soft) || !isRowCompleteForML(sharp)) continue;

                    // prefer Pinnacle as sharp when possible
                    if (!isPinnacle(sharp) && isPinnacle(soft)) {
                        GameDataB tmp = sharp; sharp = soft; soft = tmp;
                    }

                    // lower hold determines the base "sharp"
                    double holdSoft  = Math.abs(PositiveEV.impliedProbRaw(soft.getPrice1()) + PositiveEV.impliedProbRaw(soft.getPrice2()) - 1.0);
                    double holdSharp = Math.abs(PositiveEV.impliedProbRaw(sharp.getPrice1()) + PositiveEV.impliedProbRaw(sharp.getPrice2()) - 1.0);
                    if (holdSoft < holdSharp) { GameDataB t = sharp; sharp = soft; soft = t; }

                    double[] fair = PositiveEV.fairTwoWay(sharp.getPrice1(), sharp.getPrice2());
                    if (fair == null || fair.length < 2 || Double.isNaN(fair[0]) || Double.isNaN(fair[1])) continue;

                    double pHome = fair[0], pAway = fair[1];
                    double evHome = PositiveEV.expectedValue(pHome, soft.getPrice1());
                    double evAway = PositiveEV.expectedValue(pAway, soft.getPrice2());
                    if (evHome <= 0 && evAway <= 0) continue;

                    boolean pickHome = evHome >= evAway;
                    double oddsUsed  = pickHome ? soft.getPrice1()  : soft.getPrice2();
                    double sharpUsed = pickHome ? sharp.getPrice1() : sharp.getPrice2();
                    String pickTeamId = pickHome ? asStr(soft.getTeamId()) : asStr(soft.getaTeamId());
                    boolean success = actualWinnerId != null && actualWinnerId.equals(pickTeamId);

                    double dec = PositiveEV.decimalOdds(oddsUsed);
                    double profit = success ? (dec - 1.0) * STAKE_FRACTION : -1.0 * STAKE_FRACTION;
                    double evUsed = pickHome ? evHome : evAway;

                    sims.add(new SimulatedResult(
                            gameId, date, pickTeamId,
                            "Positive EV", "MoneyLine",
                            oddsUsed, sharpUsed,
                            success, profit,
                            evUsed
                    ));
                    kept++;
                }
            }
        }

        CsvWriter.writeResults(sims, outputCsvPath);
        System.out.println("[EV-ML] games=" + byGame.size() + " rows_written=" + kept + " → " + outputCsvPath);
    }

    //EV Spread
    public void simulatePositiveEV_Spread(String spreadJsonPath, String allGamesJsonPath, String outputCsvPath) {
        System.out.println("[EV-Spread] ts=" + System.currentTimeMillis());

        List<GameDataB> lineList    = GameDataB.loadAll(spreadJsonPath);
        List<GameDataB> outcomeList = GameDataB.loadAll(allGamesJsonPath);

        int nRows = (lineList == null ? 0 : lineList.size());
        System.out.println("[EV-Spread] input_rows=" + nRows);

      
        Map<String, Set<String>> bk = new HashMap<>();
        for (GameDataB g : lineList) {
            if (g == null || g.getGameId() == null) continue;
            String b = (g.getBookName() != null) ? g.getBookName()
                    : (g.getBookId() == null ? "unknown" : ("book#" + g.getBookId()));
            bk.computeIfAbsent(g.getGameId(), k -> new HashSet<>()).add(b);
        }
        int ge2 = 0; for (Map.Entry<String, Set<String>> x : bk.entrySet()) if (x.getValue().size() >= 2) ge2++;
        System.out.println("[EV-Spread] games_with_ge2_books=" + ge2 + " of " + bk.size());

        // outcomes map
        Map<String, GameDataB> outcomeBy = new HashMap<>();
        for (GameDataB g : outcomeList) if (g != null && g.getGameId() != null) outcomeBy.put(g.getGameId(), g);

        // keep only rows with full spread+price info
        Map<String, List<GameDataB>> byGame = new HashMap<>();
        for (GameDataB g : lineList) {
            if (g == null || g.getGameId() == null) continue;
            if (!isRowCompleteForSpread(g)) continue;
            byGame.computeIfAbsent(g.getGameId(), k -> new ArrayList<>()).add(g);
        }

        List<SimulatedResult> finals = new ArrayList<>();
        List<String[]> candidates = new ArrayList<>();
        candidates.add(new String[]{
                "gameId","softBook","sharpBook",
                "softP1","softP2","sharpP1","sharpP2",
                "softS1","softS2","sharpS1","sharpS2",
                "lineDiff","pHome","pAway","evHome","evAway","kept","reason"
        });

        final double lineTol = 1.0; 
        int games = 0, pairs = 0, kept = 0;

        for (Map.Entry<String, List<GameDataB>> e : byGame.entrySet()) {
            games++;
            String gameId = e.getKey();
            List<GameDataB> rows = e.getValue();

            // one row per book (latest)
            Map<String, GameDataB> perBook = new LinkedHashMap<>();
            for (GameDataB r : rows) {
                String key = (r.getBookName() != null) ? r.getBookName()
                        : (r.getBookId() == null ? "unknown" : ("book#" + r.getBookId()));
                perBook.putIfAbsent(key, r);
            }
            List<Map.Entry<String, GameDataB>> books = new ArrayList<>(perBook.entrySet());
            if (books.size() < 2) continue;

            GameDataB actual = outcomeBy.get(gameId);
            Integer hs = (actual == null ? null : actual.getHomeScore());
            Integer as = (actual == null ? null : actual.getAwayScore());
            String date = (actual == null ? null : actual.getDate());

            for (int i = 0; i < books.size(); i++) {
                for (int j = 0; j < books.size(); j++) {
                    if (i == j) continue;

                    String softKey = books.get(i).getKey();
                    String sharpKey = books.get(j).getKey();
                    GameDataB soft  = books.get(i).getValue();
                    GameDataB sharp = books.get(j).getValue();

                    if (!isRowCompleteForSpread(soft) || !isRowCompleteForSpread(sharp)) continue;

                    // Align sides by teamId if available; else by sign
                    double s1 = soft.getSpread1(), s2 = soft.getSpread2();
                    double sh1 = sharp.getSpread1(), sh2 = sharp.getSpread2();

                    Integer stH = soft.getTeamId(), stA = soft.getaTeamId();
                    Integer shH = sharp.getTeamId(), shA = sharp.getaTeamId();

                    if (stH != null && stA != null && shH != null && shA != null) {
                        boolean same = stH.equals(shH) && stA.equals(shA);
                        if (!same) { double t1 = sh1, t2 = sh2; sh1 = -t2; sh2 = -t1; }
                    } else {
                        // fallback by sign convention
                        if ((s1 < 0 && sh1 > 0) || (s1 > 0 && sh1 < 0)) { double t1 = sh1, t2 = sh2; sh1 = -t2; sh2 = -t1; }
                    }

                    double lineDiff = Math.max(Math.abs(s1 - sh1), Math.abs(s2 - sh2));

                    // choose lower-hold as "sharp"
                    double holdSoft  = Math.abs(PositiveEV.impliedProbRaw(soft.getPrice1()) + PositiveEV.impliedProbRaw(soft.getPrice2()) - 1.0);
                    double holdSharp = Math.abs(PositiveEV.impliedProbRaw(sharp.getPrice1()) + PositiveEV.impliedProbRaw(sharp.getPrice2()) - 1.0);
                    GameDataB baseSharp = holdSharp <= holdSoft ? sharp : soft;
                    GameDataB baseSoft  = holdSharp <= holdSoft ? soft  : sharp;
                    boolean swappedBase = baseSoft != soft;
                    if (swappedBase) {
                        double ts1 = s1, ts2 = s2, tsh1 = sh1, tsh2 = sh2;
                        s1 = tsh1; s2 = tsh2; sh1 = ts1; sh2 = ts2;
                        softKey = books.get(j).getKey();
                        sharpKey = books.get(i).getKey();
                    }

                    double[] fair = PositiveEV.fairTwoWay(baseSharp.getPrice1(), baseSharp.getPrice2());
                    if (fair == null || fair.length < 2 || Double.isNaN(fair[0]) || Double.isNaN(fair[1])) {
                        candidates.add(new String[]{ gameId,softKey,sharpKey,
                                sv(soft.getPrice1()),sv(soft.getPrice2()),
                                sv(sharp.getPrice1()),sv(sharp.getPrice2()),
                                sv(s1),sv(s2),sv(sh1),sv(sh2),
                                fmt(lineDiff),"","","","","0","badFair" });
                        continue;
                    }

                    double pHome = fair[0], pAway = fair[1];
                    double evHome = PositiveEV.expectedValue(pHome, baseSoft.getPrice1());
                    double evAway = PositiveEV.expectedValue(pAway, baseSoft.getPrice2());

                    boolean okLine = lineDiff <= lineTol;
                    boolean okEV   = (evHome > 0) || (evAway > 0);
                    boolean keep   = okLine && okEV;

                    candidates.add(new String[]{
                            gameId, softKey, sharpKey,
                            sv(soft.getPrice1()), sv(soft.getPrice2()),
                            sv(sharp.getPrice1()), sv(sharp.getPrice2()),
                            sv(s1), sv(s2), sv(sh1), sv(sh2),
                            fmt(lineDiff),
                            fmt(pHome), fmt(pAway), fmt(evHome), fmt(evAway),
                            keep ? "1" : "0",
                            keep ? "ok" : (!okLine ? "lineMismatch" : "noEdge")
                    });

                    if (!keep) continue;

                    pairs++;
                    boolean pickHome = evHome >= evAway;
                    double oddsUsed  = pickHome ? baseSoft.getPrice1() : baseSoft.getPrice2();
                    double sharpUsed = pickHome ? baseSharp.getPrice1() : baseSharp.getPrice2();
                    Double spread    = pickHome ? s1 : s2;
                    String pickTeamId = pickHome ? asStr(baseSoft.getTeamId()) : asStr(baseSoft.getaTeamId());

                    boolean success = false, pushed = false, graded = false;
                    if (hs != null && as != null && spread != null) {
                        int home = hs, away = as;
                        if (pickHome) {
                            int cmp = Double.compare(home + spread, away);
                            success = (cmp > 0); pushed = (cmp == 0);
                        } else {
                            int cmp = Double.compare(away + spread, home);
                            success = (cmp > 0); pushed = (cmp == 0);
                        }
                        graded = true;
                    }

                    double dec = PositiveEV.decimalOdds(oddsUsed);
                    double profit = graded ? (pushed ? 0.0 : (success ? (dec - 1.0) * STAKE_FRACTION : -1.0 * STAKE_FRACTION)) : 0.0;
                    double evUsed = pickHome ? evHome : evAway;

                    finals.add(new SimulatedResult(
                            gameId, date, pickTeamId,
                            "Positive EV", "Spread",
                            oddsUsed, sharpUsed,
                            success, profit,
                            evUsed
                    ));
                    kept++;
                }
            }
        }

        // Ensure output dir
        try {
            java.nio.file.Path outPath = java.nio.file.Paths.get(outputCsvPath);
            java.nio.file.Files.createDirectories(outPath.getParent());
        } catch (Exception ignore) {}

        // Write candidates CSV near the output file
        java.nio.file.Path candPath = java.nio.file.Paths.get(new java.io.File(outputCsvPath).getParent(), "pev_spread_candidates.csv");
        try {
            java.nio.file.Files.createDirectories(candPath.getParent());
            try (java.io.PrintWriter pw = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(candPath))) {
                for (String[] row : candidates) pw.println(String.join(",", row));
            }
            System.out.println("[EV-Spread] wrote candidates → " + candPath);
        } catch (Exception ex) {
            System.out.println("[EV-Spread] candidate write failed: " + ex.getMessage());
        }

        // Forced-output fallback (top candidates by EV) if finals empty
        if (finals.isEmpty() && candidates.size() > 1) {
            List<String[]> body = new ArrayList<>(candidates.subList(1, candidates.size()));
            body.sort((a,b) -> {
                double aMax = Math.max(parseD(a[15]), parseD(a[16]));
                double bMax = Math.max(parseD(b[15]), parseD(b[16]));
                return Double.compare(bMax, aMax);
            });
            int take = Math.min(200, body.size());
            for (int k = 0; k < take; k++) {
                String[] r = body.get(k);
                String gameId = r[0];
                double evH = parseD(r[15]);
                double evA = parseD(r[16]);
                boolean pickHome = evH >= evA;
                double oddsUsed  = parseD(pickHome ? r[3] : r[4]);
                double sharpUsed = parseD(pickHome ? r[5] : r[6]);
                finals.add(new SimulatedResult(
                        gameId, null, null,
                        "Positive EV", "Spread",
                        oddsUsed, sharpUsed,
                        false, 0.0,
                        pickHome ? evH : evA
                ));
            }
            System.out.println("[EV-Spread] Forced output: wrote top " + take + " candidate rows to final CSV");
        }

        System.out.println("[EV-Spread] finals_size=" + finals.size());
        CsvWriter.writeResults(finals, outputCsvPath);
        System.out.println("[EV-Spread] rows_written=" + finals.size() + " candidates=" + (candidates.size() - 1));
        System.out.println("Spread EV simulation complete → " + outputCsvPath);
    }

      //Totals
    public void simulatePositiveEV_Totals(String totalsJsonPath, String allGamesJsonPath, String csvOutPath) {
        List<GameDataB> totals   = GameDataB.loadAll(totalsJsonPath);
        List<GameDataB> outcomes = GameDataB.loadAll(allGamesJsonPath);

        Map<String, GameDataB> outcomeByGame = new HashMap<>();
        for (GameDataB g : outcomes) if (g != null && g.getGameId() != null) outcomeByGame.put(g.getGameId(), g);

        Map<String, List<GameDataB>> byGame = new HashMap<>();
        for (GameDataB r : totals) {
            if (r == null || r.getGameId() == null) continue;
            if (r.getPrice1() == null || r.getPrice2() == null) continue;
            // totals must have total1/total2 lines present
            if (r.getTotal1() == null || r.getTotal2() == null) continue;
            byGame.computeIfAbsent(r.getGameId(), k -> new ArrayList<>()).add(r);
        }

        List<SimulatedResult> sims = new ArrayList<>();
        final double eps = 0.25; // required line alignment across books

        for (Map.Entry<String, List<GameDataB>> entry : byGame.entrySet()) {
            String gameId = entry.getKey();
            List<GameDataB> rows = entry.getValue();
            if (rows.size() < 2) continue;

            GameDataB actual = outcomeByGame.get(gameId);
            String date = (actual == null) ? null : actual.getDate();
            Integer hs = (actual == null) ? null : actual.getHomeScore();
            Integer as = (actual == null) ? null : actual.getAwayScore();

            for (int j = 0; j < rows.size(); j++) {
                GameDataB sharp = rows.get(j);
                if (!hasTotalsAndPrices(sharp)) continue;

                for (int i = 0; i < rows.size(); i++) {
                    if (i == j) continue;
                    GameDataB soft = rows.get(i);
                    if (!hasTotalsAndPrices(soft)) continue;

                    // require same line (within eps)
                    Double shOver = sharp.getTotal1(), shUnder = sharp.getTotal2();
                    Double soOver = soft.getTotal1(),  soUnder = soft.getTotal2();
                    if (shOver == null || shUnder == null || soOver == null || soUnder == null) continue;
                    if (Math.abs(shOver - soOver) > eps || Math.abs(shUnder - soUnder) > eps) continue;

                    // fair probs from sharp; EV at soft
                    double[] fair = PositiveEV.fairTwoWay(sharp.getPrice1(), sharp.getPrice2());
                    if (fair == null || fair.length < 2) continue;
                    double pOver = fair[0], pUnder = fair[1];

                    double evOver  = PositiveEV.expectedValue(pOver,  soft.getPrice1());
                    double evUnder = PositiveEV.expectedValue(pUnder, soft.getPrice2());
                    if (evOver <= 0 && evUnder <= 0) continue;

                    boolean pickOver = evOver >= evUnder;
                    double evUsed    = pickOver ? evOver : evUnder;
                    double oddsUsed  = pickOver ? soft.getPrice1() : soft.getPrice2();
                    double sharpUsed = pickOver ? sharp.getPrice1() : sharp.getPrice2();

                    boolean success = false, pushed = false;
                    if (hs != null && as != null) {
                        int totalScore = hs + as;
                        double line = pickOver ? soOver : soUnder;
                        if (pickOver) { if (totalScore > line) success = true; else if (Math.abs(totalScore - line) < 1e-9) pushed = true; }
                        else          { if (totalScore < line) success = true; else if (Math.abs(totalScore - line) < 1e-9) pushed = true; }
                    }

                    double dec = PositiveEV.decimalOdds(oddsUsed);
                    double profit = pushed ? 0.0 : (success ? (dec - 1.0) * STAKE_FRACTION : -1.0 * STAKE_FRACTION);

                    sims.add(new SimulatedResult(
                            gameId, date, null,
                            "Positive EV", "Totals",
                            oddsUsed, sharpUsed,
                            success, profit,
                            evUsed
                    ));
                }
            }
        }

        CsvWriter.writeResults(sims, csvOutPath);
        System.out.println("[EV-Totals] games=" + byGame.size() + " rows_written=" + sims.size() + " → " + csvOutPath);
    }

   //Arbitrage Wrappers
    public void simulateArbitrage_ML(String moneylineJsonPath, String allGamesJsonPath, String csvOutPath) {
        List<GameDataB> ml       = GameDataB.loadAll(moneylineJsonPath);
        List<GameDataB> outcomes = GameDataB.loadAll(allGamesJsonPath);
        Map<String, String> dateByGameId = buildDateMap(outcomes);
        List<SimulatedResult> arbs = Arbitrage.findMoneyLineArbs(ml, dateByGameId);
        CsvWriter.writeResults(arbs, csvOutPath);
    }

    public void simulateArbitrage_Spread(String spreadJsonPath, String allGamesJsonPath, String csvOutPath) {
        List<GameDataB> spreads  = GameDataB.loadAll(spreadJsonPath);
        List<GameDataB> outcomes = GameDataB.loadAll(allGamesJsonPath);
        Map<String, String> dateByGameId = buildDateMap(outcomes);
        List<SimulatedResult> arbs = Arbitrage.findSpreadArbs(spreads, dateByGameId);
        CsvWriter.writeResults(arbs, csvOutPath);
    }

    public void simulateArbitrage_Totals(String totalsJsonPath, String allGamesJsonPath, String csvOutPath) {
        List<GameDataB> totals   = GameDataB.loadAll(totalsJsonPath);
        List<GameDataB> outcomes = GameDataB.loadAll(allGamesJsonPath);
        Map<String, String> dateByGameId = buildDateMap(outcomes);
        List<SimulatedResult> arbs = Arbitrage.findTotalsArbs(totals, dateByGameId);
        CsvWriter.writeResults(arbs, csvOutPath);
    }

    //Helpers
    private Map<String, String> buildDateMap(List<GameDataB> outcomes) {
        Map<String, String> map = new HashMap<>();
        if (outcomes == null) return map;
        for (GameDataB g : outcomes) {
            if (g != null && g.getGameId() != null) map.put(g.getGameId(), g.getDate());
        }
        return map;
    }

    private static boolean isPinnacle(GameDataB g) {
        return g != null && g.getBookName() != null && g.getBookName().toLowerCase().contains("pinnacle");
    }

    private static boolean isRowCompleteForML(GameDataB g) {
        return g != null && g.getPrice1() != null && g.getPrice2() != null;
    }

    private static boolean isRowCompleteForSpread(GameDataB g) {
        return g != null
                && g.getPrice1() != null && g.getPrice2() != null
                && g.getSpread1() != null && g.getSpread2() != null;
    }

    private static boolean hasTotalsAndPrices(GameDataB g) {
        return g != null
                && g.getPrice1() != null && g.getPrice2() != null
                && g.getTotal1() != null && g.getTotal2() != null;
    }

    private static String asStr(Integer v) { return v == null ? null : String.valueOf(v); }

    private static String sv(Double d) { return (d == null) ? "" : String.valueOf(d); }

    private static String fmt(double x) { return String.format(java.util.Locale.US, "%.5f", x); }

    private static double parseD(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; } }


public void simulatePositiveEV_ML_Bankroll(String moneylineJsonPath,
String allGamesJsonPath,
String tradeLogCsv,
String dailyCurveCsv) {
final double START = 100.0;  // starting bankroll ($)
util.Bankroll bk = new util.Bankroll(
START,
util.Bankroll.Mode.KELLY,
0.0,        // flatPct (unused in KELLY)
0.5,        // kellyFrac (half Kelly)
0.02,       // maxStakePct (2% cap)
0.01        // minStake ($0.01)
);

List<model.GameDataB> oddsList    = model.GameDataB.loadAll(moneylineJsonPath);
List<model.GameDataB> outcomeList = model.GameDataB.loadAll(allGamesJsonPath);

Map<String, model.GameDataB> outcomeBy = new HashMap<>();
for (model.GameDataB g : outcomeList) if (g != null && g.getGameId() != null) outcomeBy.put(g.getGameId(), g);

// Build book lists per game
Map<String, List<model.GameDataB>> byGame = new HashMap<>();
for (model.GameDataB o : oddsList) {
if (o == null || o.getGameId() == null) continue;
if (o.getPrice1() == null || o.getPrice2() == null) continue;
byGame.computeIfAbsent(o.getGameId(), k -> new ArrayList<>()).add(o);
}

// trade log header
List<String[]> trades = new ArrayList<>();
trades.add(new String[]{"date","gameId","strategy","betType","side","oddsUsed","pWin","stake","profit","bankroll_after"});

// daily curve
Map<java.time.LocalDate, Double> daily = new TreeMap<>();

for (Map.Entry<String, List<model.GameDataB>> e : byGame.entrySet()) {
String gameId = e.getKey();
List<model.GameDataB> books = e.getValue();
if (books.size() < 2) continue;

model.GameDataB actual = outcomeBy.get(gameId);
String dateStr = (actual == null ? null : actual.getDate());
java.time.LocalDate day = parseDay(dateStr);
if (day == null) continue;

// choose sharp vs soft by hold (prefer Pinnacle as sharp)
for (int i = 0; i < books.size(); i++) {
for (int j = 0; j < books.size(); j++) {
if (i == j) continue;
model.GameDataB soft  = books.get(i);
model.GameDataB sharp = books.get(j);
if (!isRowCompleteForML(soft) || !isRowCompleteForML(sharp)) continue;
if (!isPinnacle(sharp) && isPinnacle(soft)) { var t = sharp; sharp = soft; soft = t; }

double holdSoft  = Math.abs(util.PositiveEV.impliedProbRaw(soft.getPrice1()) + util.PositiveEV.impliedProbRaw(soft.getPrice2()) - 1.0);
double holdSharp = Math.abs(util.PositiveEV.impliedProbRaw(sharp.getPrice1()) + util.PositiveEV.impliedProbRaw(sharp.getPrice2()) - 1.0);
if (holdSoft < holdSharp) { var t = sharp; sharp = soft; soft = t; }

double[] fair = util.PositiveEV.fairTwoWay(sharp.getPrice1(), sharp.getPrice2());
if (fair == null || fair.length < 2 || Double.isNaN(fair[0]) || Double.isNaN(fair[1])) continue;

// pick side with higher EV AT soft price
double pHome = fair[0], pAway = fair[1];
double evHome = util.PositiveEV.expectedValue(pHome, soft.getPrice1());
double evAway = util.PositiveEV.expectedValue(pAway, soft.getPrice2());
boolean pickHome = evHome >= evAway;
double pWin = pickHome ? pHome : pAway;
double american = pickHome ? soft.getPrice1() : soft.getPrice2();
double dec = util.PositiveEV.decimalOdds(american);

// stake sizing & settlement
double stake = bk.sizeStake(pWin, dec);
if (stake <= 0) continue; 
boolean success = false;
if (actual != null && actual.getWinner() != null) {
String pickTeamId = pickHome ? String.valueOf(soft.getTeamId()) : String.valueOf(soft.getaTeamId());
success = String.valueOf(actual.getWinner()).equals(pickTeamId);
}
double profit = bk.settle(stake, success, dec);

trades.add(new String[]{
dateStr == null ? "" : dateStr,
gameId, "Positive EV", "Moneyline",
pickHome ? "HOME" : "AWAY",
String.valueOf(american),
String.format(java.util.Locale.US,"%.6f", pWin),
String.format(java.util.Locale.US,"%.2f", stake),
String.format(java.util.Locale.US,"%.2f", profit),
String.format(java.util.Locale.US,"%.2f", bk.getBankroll())
});

daily.put(day, bk.getBankroll());
}
}
}

writeCsv(trades, tradeLogCsv);
writeDaily(daily, dailyCurveCsv, "Positive EV (Moneyline)", 100.0, bk.getBankroll());
System.out.println("[BK-ML] final bankroll = $" + String.format(java.util.Locale.US, "%.2f", bk.getBankroll()));
}

public void simulatePositiveEV_Spread_Bankroll(String spreadJsonPath,
    String allGamesJsonPath,
    String tradeLogCsv,
    String dailyCurveCsv) {

final double START = 100.0;
util.Bankroll bk = new util.Bankroll(START, util.Bankroll.Mode.KELLY, 0.0, 0.5, 0.02, 0.01);

List<model.GameDataB> lineList    = model.GameDataB.loadAll(spreadJsonPath);
List<model.GameDataB> outcomeList = model.GameDataB.loadAll(allGamesJsonPath);

Map<String, model.GameDataB> outcomeBy = new HashMap<>();
for (model.GameDataB g : outcomeList) if (g != null && g.getGameId() != null) outcomeBy.put(g.getGameId(), g);

Map<String, List<model.GameDataB>> byGame = new HashMap<>();
for (model.GameDataB g : lineList) {
if (g == null || g.getGameId() == null) continue;
if (!isRowCompleteForSpread(g)) continue;
byGame.computeIfAbsent(g.getGameId(), k -> new ArrayList<>()).add(g);
}

List<String[]> trades = new ArrayList<>();
trades.add(new String[]{"date","gameId","strategy","betType","side","spread","oddsUsed","pWin","stake","profit","bankroll_after"});

Map<java.time.LocalDate, Double> daily = new TreeMap<>();

for (Map.Entry<String, List<model.GameDataB>> e : byGame.entrySet()) {
String gameId = e.getKey();
List<model.GameDataB> rows = e.getValue();
if (rows.size() < 2) continue;

model.GameDataB actual = outcomeBy.get(gameId);
String dateStr = (actual == null ? null : actual.getDate());
java.time.LocalDate day = parseDay(dateStr);
if (day == null) continue;

// one row per book (latest)
Map<String, model.GameDataB> perBook = new LinkedHashMap<>();
for (model.GameDataB r : rows) {
String key = (r.getBookName() != null) ? r.getBookName()
: (r.getBookId() == null ? "unknown" : ("book#" + r.getBookId()));
perBook.putIfAbsent(key, r);
}
List<Map.Entry<String, model.GameDataB>> books = new ArrayList<>(perBook.entrySet());
if (books.size() < 2) continue;

for (int i = 0; i < books.size(); i++) {
for (int j = 0; j < books.size(); j++) {
if (i == j) continue;

model.GameDataB soft  = books.get(i).getValue();
model.GameDataB sharp = books.get(j).getValue();
if (!isRowCompleteForSpread(soft) || !isRowCompleteForSpread(sharp)) continue;

double s1 = soft.getSpread1(), s2 = soft.getSpread2();
double sh1 = sharp.getSpread1(), sh2 = sharp.getSpread2();

Integer stH = soft.getTeamId(), stA = soft.getaTeamId();
Integer shH = sharp.getTeamId(), shA = sharp.getaTeamId();
if (stH != null && stA != null && shH != null && shA != null) {
boolean same = stH.equals(shH) && stA.equals(shA);
if (!same) { double t1 = sh1, t2 = sh2; sh1 = -t2; sh2 = -t1; }
} else {
if ((s1 < 0 && sh1 > 0) || (s1 > 0 && sh1 < 0)) { double t1 = sh1, t2 = sh2; sh1 = -t2; sh2 = -t1; }
}

// choose lower-hold as "sharp"
double holdSoft  = Math.abs(util.PositiveEV.impliedProbRaw(soft.getPrice1()) + util.PositiveEV.impliedProbRaw(soft.getPrice2()) - 1.0);
double holdSharp = Math.abs(util.PositiveEV.impliedProbRaw(sharp.getPrice1()) + util.PositiveEV.impliedProbRaw(sharp.getPrice2()) - 1.0);
model.GameDataB baseSharp = holdSharp <= holdSoft ? sharp : soft;
model.GameDataB baseSoft  = holdSharp <= holdSoft ? soft  : sharp;
if (baseSoft != soft) {
double ts1 = s1, ts2 = s2, tsh1 = sh1, tsh2 = sh2;
s1 = tsh1; s2 = tsh2; sh1 = ts1; sh2 = ts2;
}

double[] fair = util.PositiveEV.fairTwoWay(baseSharp.getPrice1(), baseSharp.getPrice2());
if (fair == null || fair.length < 2 || Double.isNaN(fair[0]) || Double.isNaN(fair[1])) continue;

double pHome = fair[0], pAway = fair[1];
double evHome = util.PositiveEV.expectedValue(pHome, baseSoft.getPrice1());
double evAway = util.PositiveEV.expectedValue(pAway, baseSoft.getPrice2());
boolean pickHome = evHome >= evAway;

double pWin = pickHome ? pHome : pAway;
double american = pickHome ? baseSoft.getPrice1() : baseSoft.getPrice2();
double spread = pickHome ? s1 : s2;
double dec = util.PositiveEV.decimalOdds(american);

// Grade outcome for win
boolean success = false, pushed = false;
if (actual != null && actual.getHomeScore() != null && actual.getAwayScore() != null) {
int home = actual.getHomeScore(), away = actual.getAwayScore();
int cmp = pickHome ? Double.compare(home + spread, away) : Double.compare(away + spread, home);
success = (cmp > 0); pushed = (cmp == 0);
}

double stake = bk.sizeStake(pWin, dec);
if (stake <= 0) continue;
double profit = pushed ? 0.0 : bk.settle(stake, success, dec);

trades.add(new String[]{
dateStr == null ? "" : dateStr,
gameId, "Positive EV", "Spread",
pickHome ? "HOME" : "AWAY",
String.format(java.util.Locale.US,"%.2f", spread),
String.valueOf(american),
String.format(java.util.Locale.US,"%.6f", pWin),
String.format(java.util.Locale.US,"%.2f", stake),
String.format(java.util.Locale.US,"%.2f", profit),
String.format(java.util.Locale.US,"%.2f", bk.getBankroll())
});

daily.put(day, bk.getBankroll());
}
}
}

writeCsv(trades, tradeLogCsv);
writeDaily(daily, dailyCurveCsv, "Positive EV (Spread)", 100.0, bk.getBankroll());
System.out.println("[BK-Spread] final bankroll = $" + String.format(java.util.Locale.US, "%.2f", bk.getBankroll()));
}

public void simulatePositiveEV_Totals_Bankroll(String totalsJsonPath,
    String allGamesJsonPath,
    String tradeLogCsv,
    String dailyCurveCsv) {
final double START = 100.0;
util.Bankroll bk = new util.Bankroll(START, util.Bankroll.Mode.KELLY, 0.0, 0.5, 0.02, 0.01);

List<model.GameDataB> totals   = model.GameDataB.loadAll(totalsJsonPath);
List<model.GameDataB> outcomes = model.GameDataB.loadAll(allGamesJsonPath);

Map<String, model.GameDataB> outcomeBy = new HashMap<>();
for (model.GameDataB g : outcomes) if (g != null && g.getGameId() != null) outcomeBy.put(g.getGameId(), g);

Map<String, List<model.GameDataB>> byGame = new HashMap<>();
for (model.GameDataB r : totals) {
if (r == null || r.getGameId() == null) continue;
if (r.getPrice1() == null || r.getPrice2() == null) continue;
if (r.getTotal1() == null || r.getTotal2() == null) continue;
byGame.computeIfAbsent(r.getGameId(), k -> new ArrayList<>()).add(r);
}

List<String[]> trades = new ArrayList<>();
trades.add(new String[]{"date","gameId","strategy","betType","side","total","oddsUsed","pWin","stake","profit","bankroll_after"});

Map<java.time.LocalDate, Double> daily = new TreeMap<>();

for (Map.Entry<String, List<model.GameDataB>> entry : byGame.entrySet()) {
String gameId = entry.getKey();
List<model.GameDataB> rows = entry.getValue();
if (rows.size() < 2) continue;

model.GameDataB actual = outcomeBy.get(gameId);
String dateStr = (actual == null ? null : actual.getDate());
java.time.LocalDate day = parseDay(dateStr);
if (day == null) continue;

for (int j = 0; j < rows.size(); j++) {
model.GameDataB sharp = rows.get(j);
if (!hasTotalsAndPrices(sharp)) continue;

for (int i = 0; i < rows.size(); i++) {
if (i == j) continue;
model.GameDataB soft = rows.get(i);
if (!hasTotalsAndPrices(soft)) continue;

Double shOver = sharp.getTotal1(), shUnder = sharp.getTotal2();
Double soOver = soft.getTotal1(),  soUnder = soft.getTotal2();
if (shOver == null || shUnder == null || soOver == null || soUnder == null) continue;
if (Math.abs(shOver - soOver) > 0.25 || Math.abs(shUnder - soUnder) > 0.25) continue;

double[] fair = util.PositiveEV.fairTwoWay(sharp.getPrice1(), sharp.getPrice2());
if (fair == null || fair.length < 2) continue;
double pOver  = fair[0], pUnder = fair[1];

double evOver  = util.PositiveEV.expectedValue(pOver,  soft.getPrice1());
double evUnder = util.PositiveEV.expectedValue(pUnder, soft.getPrice2());
boolean pickOver = evOver >= evUnder;

double pWin = pickOver ? pOver : pUnder;
double american = pickOver ? soft.getPrice1() : soft.getPrice2();
double total = pickOver ? soOver : soUnder;
double dec = util.PositiveEV.decimalOdds(american);

boolean success = false, pushed = false;
if (actual != null && actual.getHomeScore() != null && actual.getAwayScore() != null) {
int totalScore = actual.getHomeScore() + actual.getAwayScore();
if (pickOver) { if (totalScore > total) success = true; else if (Math.abs(totalScore - total) < 1e-9) pushed = true; }
else { if (totalScore < total) success = true; else if (Math.abs(totalScore - total) < 1e-9) pushed = true; }
}

double stake = bk.sizeStake(pWin, dec);
if (stake <= 0) continue;
double profit = pushed ? 0.0 : bk.settle(stake, success, dec);

trades.add(new String[]{
dateStr == null ? "" : dateStr,
gameId, "Positive EV", "Totals",
pickOver ? "OVER" : "UNDER",
String.format(java.util.Locale.US,"%.2f", total),
String.valueOf(american),
String.format(java.util.Locale.US,"%.6f", pWin),
String.format(java.util.Locale.US,"%.2f", stake),
String.format(java.util.Locale.US,"%.2f", profit),
String.format(java.util.Locale.US,"%.2f", bk.getBankroll())
});

daily.put(day, bk.getBankroll());
}
}
}

writeCsv(trades, tradeLogCsv);
writeDaily(daily, dailyCurveCsv, "Positive EV (Totals)", 100.0, bk.getBankroll());
System.out.println("[BK-Totals] final bankroll = $" + String.format(java.util.Locale.US, "%.2f", bk.getBankroll()));
}

private static java.time.LocalDate parseDay(String iso) {
    try {
        if (iso == null) return null;
        // try datetime
        return java.time.OffsetDateTime.parse(iso).toLocalDate();
    } catch (Exception e) {
        try { return java.time.LocalDate.parse(iso); } catch (Exception ex) { return null; }
    }
}

private static void writeCsv(List<String[]> rows, String path) {
    try {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        java.nio.file.Files.createDirectories(p.getParent());
        try (java.io.PrintWriter pw = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(p))) {
            for (String[] r : rows) pw.println(String.join(",", r));
        }
        System.out.println("Wrote trades → " + path);
    } catch (Exception e) {
        System.out.println("Trade log write failed: " + e.getMessage());
    }
}

private static void writeDaily(Map<java.time.LocalDate, Double> daily,
                               String path,
                               String label,
                               double start,
                               double end) {
    try {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        java.nio.file.Files.createDirectories(p.getParent());
        try (java.io.PrintWriter pw = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(p))) {
            pw.println("date,bankroll");
            for (Map.Entry<java.time.LocalDate, Double> e : daily.entrySet()) {
                pw.println(e.getKey() + "," + String.format(java.util.Locale.US,"%.2f", e.getValue()));
            }
        }
        System.out.println("Wrote daily curve (" + label + ") → " + path +
                " | start=$" + String.format(java.util.Locale.US,"%.2f", start) +
                " end=$" + String.format(java.util.Locale.US,"%.2f", end));
    } catch (Exception e) {
        System.out.println("Daily curve write failed: " + e.getMessage());
    }
}





}
