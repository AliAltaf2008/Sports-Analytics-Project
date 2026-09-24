package main;

import simulator.Simulator;
import java.io.File;

public class Main {

    public static void main(String[] args) {
        // Debug banner to know the build is running
        System.out.println("Main build tag: BK_ENABLED");

        if (args.length < 2) {
            printUsage();
            return;
        }

        String section = args[0].toLowerCase();   // positiveEV | arbitrage | middle | bankroll
        String market  = args[1].toLowerCase();   // ml | spread | totals | all

        // Optional inputs: [marketPath] [allGamesPath]
        String marketPath = argOr(args, 2, "data/" + defaultMarketDir(market));
        String allPath    = argOr(args, 3, "data/all_games");

        ensureDir("outputdata");
        Simulator sim = new Simulator();

        try {
            if (section.equals("positiveev") || section.equals("ev") || section.equals("positive")) {
                runEV(sim, market, marketPath, allPath);
            } else if (section.equals("arbitrage") || section.equals("arb")) {
                runArbitrage(sim, market, marketPath, allPath);
            } else if (section.equals("bankroll") || section.equals("bk")) {
                runBankroll(sim, market, marketPath, allPath);
            } else {
                System.out.println("Unknown section: " + section);
                printUsage();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void runEV(Simulator sim, String market, String marketPath, String allPath) throws Exception {
        if (isML(market)) {
            String outCsv = outFile("positiveEV", "ML");
            sim.simulatePositiveEV_ML(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isSpread(market)) {
            String outCsv = outFile("positiveEV", "Spread");
            sim.simulatePositiveEV_Spread(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isTotals(market)) {
            String outCsv = outFile("positiveEV", "Totals");
            sim.simulatePositiveEV_Totals(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isAll(market)) {
            runEV(sim, "ml",     marketPath, allPath);
            runEV(sim, "spread", marketPath, allPath);
            runEV(sim, "totals", marketPath, allPath);
        } else {
            System.out.println("Unknown market for positiveEV: " + market);
        }
    }

    private static void runArbitrage(Simulator sim, String market, String marketPath, String allPath) throws Exception {
        if (isML(market)) {
            String outCsv = outFile("arbitrage", "ML");
            sim.simulateArbitrage_ML(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isSpread(market)) {
            String outCsv = outFile("arbitrage", "Spread");
            sim.simulateArbitrage_Spread(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isTotals(market)) {
            String outCsv = outFile("arbitrage", "Totals");
            sim.simulateArbitrage_Totals(marketPath, allPath, outCsv);
            System.out.println("Wrote: " + outCsv);
        } else if (isAll(market)) {
            runArbitrage(sim, "ml",     marketPath, allPath);
            runArbitrage(sim, "spread", marketPath, allPath);
            runArbitrage(sim, "totals", marketPath, allPath);
        } else {
            System.out.println("Unknown market for arbitrage: " + market);
        }
    }


    private static void runBankroll(Simulator sim, String market, String marketPath, String allPath) throws Exception {
        String outDir = "/Users/alialtaf/Documents/BettingSimulation/outputdata";
        ensureDir(outDir);

        if (isML(market)) {
            sim.simulatePositiveEV_ML_Bankroll(
                    marketPath, allPath,
                    outDir + "/bk_ml_trades.csv",
                    outDir + "/bk_ml_daily.csv");
            System.out.println("Wrote: bk_ml_trades.csv and bk_ml_daily.csv");
        } else if (isSpread(market)) {
            sim.simulatePositiveEV_Spread_Bankroll(
                    marketPath, allPath,
                    outDir + "/bk_spread_trades.csv",
                    outDir + "/bk_spread_daily.csv");
            System.out.println("Wrote: bk_spread_trades.csv and bk_spread_daily.csv");
        } else if (isTotals(market)) {
            sim.simulatePositiveEV_Totals_Bankroll(
                    marketPath, allPath,
                    outDir + "/bk_totals_trades.csv",
                    outDir + "/bk_totals_daily.csv");
            System.out.println("Wrote: bk_totals_trades.csv and bk_totals_daily.csv");
        } else if (isAll(market)) {
            runBankroll(sim, "ml",     marketPath, allPath);
            runBankroll(sim, "spread", marketPath, allPath);
            runBankroll(sim, "totals", marketPath, allPath);
        } else {
            System.out.println("Unknown market for bankroll: " + market);
        }
    }

    private static boolean isML(String s)     { return s.equals("ml") || s.equals("moneyline"); }
    private static boolean isSpread(String s) { return s.equals("spread"); }
    private static boolean isTotals(String s) { return s.equals("totals") || s.equals("total"); }
    private static boolean isAll(String s)    { return s.equals("all"); }

    private static String argOr(String[] a, int idx, String def) {
        if (idx < a.length && a[idx] != null && a[idx].length() > 0) return a[idx];
        return def;
    }

    private static String defaultMarketDir(String market) {
        if (isML(market)) return "moneyline";
        if (isSpread(market)) return "spread";
        if (isTotals(market)) return "totals";
        return "moneyline";
    }

    private static void ensureDir(String folder) {
        File f = new File(folder);
        if (!f.exists()) {
            boolean ok = f.mkdirs();
            if (!ok) System.out.println("Warning: could not create folder: " + folder);
        }
    }

    private static String outFile(String strategy, String marketCamel) {
        String base = "/Users/alialtaf/Documents/BettingSimulation/outputdata";
        ensureDir(base);
        return base + File.separator + strategy + marketCamel + ".csv";
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -cp \"out:lib/*\" main.Main <section> <market> [marketPath] [allGamesPath]");
        System.out.println();
        System.out.println("Sections: positiveEV | arbitrage | middle | bankroll   (aliases: ev | arb | mid | bk)");
        System.out.println("Markets:  ml | spread | totals | all");
        System.out.println();
        System.out.println("Outputs go in: outputdata/<strategy><Market>.csv   e.g., arbitrageML.csv, positiveEVSpread.csv");
        System.out.println("For bankroll runs, outputs are bk_<market>_trades.csv and bk_<market>_daily.csv");
    }
}
