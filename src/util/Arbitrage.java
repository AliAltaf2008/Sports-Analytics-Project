package util;

import model.GameDataB;
import model.SimulatedResult;

import java.util.*;

public class Arbitrage {
    private static final double EPS = 1e-12;

    public static List<SimulatedResult> findMoneyLineArbs(List<GameDataB> moneylines, Map<String, String> dateByGameId) {
        return findTwoWayArbs(moneylines, dateByGameId, "Moneyline");
    }

    public static List<SimulatedResult> findSpreadArbs(List<GameDataB> spreads, Map<String, String> dateByGameId) {
        return findTwoWayArbs(spreads, dateByGameId, "Spread");
    }

    public static List<SimulatedResult> findTotalsArbs(List<GameDataB> totals, Map<String, String> dateByGameId) {
        return findTwoWayArbs(totals, dateByGameId, "Totals");
    }

    private static List<SimulatedResult> findTwoWayArbs(List<GameDataB> rows, Map<String, String> dateByGameId, String betType) {
        List<SimulatedResult> out = new ArrayList<>();
        if(rows == null || rows.isEmpty()) return out;

        Map<String, List<GameDataB>> byGame = new HashMap<>();
        for(GameDataB r : rows) {
            if(r== null || r.getGameId() == null) continue;
            byGame.computeIfAbsent(r.getGameId(), k -> new ArrayList<>()).add(r);
        }

        for(Map.Entry<String, List<GameDataB>> entry : byGame.entrySet()) {
            String gameId = entry.getKey();
            List<GameDataB> list = entry.getValue();
            if(list.size() < 2) continue;

            String date = (dateByGameId == null) ? null : dateByGameId.get(gameId);

            for(int i =0; i<list.size(); i++) {
                GameDataB A = list.get(i);
                if(!hasBothPrices(A)) continue;

                for(int j = i+1; j < list.size(); j++) {
                    GameDataB B = list.get(j);
                    if(sameBook(A, B)) continue;

                testAndAddArb(out, betType, gameId, date, A.getPrice1(), B.getPrice2());

                testAndAddArb(out, betType, gameId, date, A.getPrice2(), B.getPrice1());
                }
            }
        }
        return out;
    }

    private static boolean hasBothPrices(GameDataB r) {
        return r != null && r.getPrice1() != null && r.getPrice2() != null;
    }

    private static boolean sameBook(GameDataB a, GameDataB b ) {
        if (a == null || b == null) return true;
        if (a.getBookName() != null && b.getBookName() != null) {
            return a.getBookName().equals(b.getBookName());
        }
        Integer ai = a.getBookId();
        Integer bi = b.getBookId();
        return ai != null && ai.equals(bi);
    }

    private static void testAndAddArb(List<SimulatedResult> out, String betType, String gameId, String date, double side1American, double side2American) {
        double d1 = PositiveEV.decimalOdds(side1American);
        double d2 = PositiveEV.decimalOdds(side2American);
        if(d1 <= 1.0 || d2 <= 1.0) return;

        double sumImplied = 1.0 / d1 + 1.0 / d2;
        if (sumImplied + EPS < 1.0) {
            double profit = 1.0 - sumImplied;
            out.add(new SimulatedResult(gameId, date, betType, side1American, side2American, profit));
        }
    }
}
