package util;

import java.io.FileWriter;
import java.util.List;
import model.SimulatedResult;

public class CsvWriter {
    public static void writeResults(List<SimulatedResult> results, String filePath) {
        try (FileWriter fw = new FileWriter(filePath)) {
            fw.append("gameId,date,pickTeamId,strategy,betType,oddsUsed,sharpOdds,success,profit,ev\n");

            for(SimulatedResult r : results) {
                fw.append(r.getGameId() == null ? "" : r.getGameId()).append(",");
                fw.append(r.getDate() == null ? "" : r.getDate()).append(",");
                fw.append(r.getPickTeamId() == null ? "" : r.getPickTeamId()).append(",");
                fw.append(r.getStrategy() == null ? "" : r.getStrategy()).append(",");
                fw.append(r.getBetType() == null ? "" : r.getBetType()).append(",");
                fw.append(String.valueOf(r.getOddsUsed())).append(",");
                fw.append(String.valueOf(r.getSharpOdds())).append(",");
                fw.append(String.valueOf(r.isSuccess())).append(",");
                fw.append(String.valueOf(r.getProfit())).append(",");
                fw.append(String.valueOf(r.getEv())).append("\n");
            }
        } catch (Exception e) {
            System.err.println("Error writing CSV" + e.getMessage());
        }
    }
}
