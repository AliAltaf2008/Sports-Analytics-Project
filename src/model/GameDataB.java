package model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameDataB {
    private String gameId;
    private String date;

    private String bookName;
    private Integer bookId;
    private Integer teamId;
    private Integer aTeamId;

    private Double price1;
    private Double price2;

    private Double spread1;
    private Double spread2;

    private Double total1;
    private Double total2;

    // outcome from AllGames.json
    private Integer homeTeamId;
    private Integer awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private Integer homeScore;
    private Integer awayScore;
    private Integer winner;

    public String  getGameId()        { return gameId; }
    public String  getDate()          { return date; }
    public String  getBookName()      { return bookName; }
    public Integer getBookId()        { return bookId; }
    public Integer getTeamId()        { return teamId; }
    public Integer getaTeamId()       { return aTeamId; }
    public Double  getPrice1()        { return price1; }
    public Double  getPrice2()        { return price2; }
    public Double  getSpread1()       { return spread1; }
    public Double  getSpread2()       { return spread2; }
    public Double  getTotal1()        { return total1; }
    public Double  getTotal2()        { return total2; }
    public Integer getHomeTeamId()    { return homeTeamId; }
    public Integer getAwayTeamId()    { return awayTeamId; }
    public String  getHomeTeamName()  { return homeTeamName; }
    public String  getAwayTeamName()  { return awayTeamName; }
    public Integer getHomeScore()     { return homeScore; }
    public Integer getAwayScore()     { return awayScore; }
    public Integer getWinner()        { return winner; }

    @JsonIgnore public boolean hasOutcome()   { return homeScore != null && awayScore != null; }
    @JsonIgnore public boolean hasSpread()    { return spread1 != null || spread2 != null; }
    @JsonIgnore public boolean hasTotals()    { return total1 != null || total2 != null; }
    @JsonIgnore public boolean hasMoneyline() {
        return (price1 != null || price2 != null) && !hasSpread() && !hasTotals();
    }

    public static List<GameDataB> loadAll (String filePath) {
        try {
            String lower = filePath.toLowerCase();

            // Ignore these by design
            if (lower.contains("oddsdataa.json") || lower.contains("oddsdatab.json")) {
                return Collections.emptyList();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new File(filePath));
            if (root == null || !root.isArray() || root.size() == 0) {
                return Collections.emptyList();
            }

            // filename detection
            if (lower.contains("allgames"))   return parseAllGamesArray(root);
            if (lower.contains("moneyline"))  return parseMoneylineArray(root);
            if (lower.contains("spread"))     return parseSpreadArray(root);
            if (lower.contains("totals"))     return parseTotalsArray(root);

            // fallback: key sniffing
            JsonNode first = root.get(0);
            if (first.has("gameDate") || first.has("hometeamId") || first.has("homeScore"))
                return parseAllGamesArray(root);
            if (first.has("spread1") || first.has("spread2"))
                return parseSpreadArray(root);
            if (first.has("total1") || first.has("total2"))
                return parseTotalsArray(root);
            if (first.has("price1") || first.has("price2"))
                return parseMoneylineArray(root);

            return Collections.emptyList();

        } catch (Exception e) {
            System.err.println("[GameDataB] Failed to read " + filePath + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<GameDataB> parseAllGamesArray(JsonNode arr) {
        List<GameDataB> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            GameDataB g = new GameDataB();
            g.gameId       = asString(n, "gameId");
            g.date         = asString(n, "gameDate");
            g.homeTeamId   = asInt(n, "hometeamId");
            g.awayTeamId   = asInt(n, "awayteamId");
            g.homeTeamName = asString(n, "hometeamName");
            g.awayTeamName = asString(n, "awayteamName");
            g.homeScore    = asInt(n, "homeScore");
            g.awayScore    = asInt(n, "awayScore");
            g.winner       = asInt(n, "winner");
            out.add(g);
        }
        return out;
    }

    private static List<GameDataB> parseMoneylineArray(JsonNode arr) {
        List<GameDataB> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            GameDataB g = new GameDataB();
            g.gameId   = asString(n, "game_id");
            g.bookName = asString(n, "book_name");
            g.bookId   = asInt(n, "book_id");
            g.teamId   = asInt(n, "team_id");
            g.aTeamId  = asInt(n, "a_team_id");
            g.price1   = asDouble(n, "price1"); // American odds
            g.price2   = asDouble(n, "price2");
            out.add(g);
        }
        return out;
    }

    private static List<GameDataB> parseSpreadArray(JsonNode arr) {
        List<GameDataB> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            GameDataB g = new GameDataB();
            g.gameId   = asString(n, "game_id");
            g.bookName = asString(n, "book_name");
            g.bookId   = asInt(n, "book_id");
            g.teamId   = asInt(n, "team_id");
            g.aTeamId  = asInt(n, "a_team_id");
            g.spread1  = asDouble(n, "spread1");
            g.spread2  = asDouble(n, "spread2");

            g.price1   = asDouble(n, "price1");  // American odds
            g.price2   = asDouble(n, "price2");

            g.total1   = asDouble(n, "total1");
            g.total2   = asDouble(n, "total2");

            out.add(g);
        }
        return out;
    }

    private static List<GameDataB> parseTotalsArray(JsonNode arr) {
        List<GameDataB> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            GameDataB g = new GameDataB();
            g.gameId   = asString(n, "game_id");
            g.bookName = asString(n, "book_name");
            g.bookId   = asInt(n, "book_id");
            g.teamId   = asInt(n, "team_id");
            g.aTeamId  = asInt(n, "a_team_id");
            g.total1   = asDouble(n, "total1");
            g.total2   = asDouble(n, "total2");
            g.price1   = asDouble(n, "price1");
            g.price2   = asDouble(n, "price2");
            out.add(g);
        }
        return out;
    }

    private static String asString(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.numberValue().toString();
        return v.asText();
    }

    private static Integer asInt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.intValue();
        if (v.isNumber()) return (int) Math.round(v.doubleValue());
        try { return Integer.valueOf(v.asText().trim()); }
        catch (Exception e) { return null; }
    }

    private static Double asDouble(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.doubleValue();
        try { return Double.valueOf(v.asText().trim()); }
        catch (Exception e) { return null; }
    }
}
