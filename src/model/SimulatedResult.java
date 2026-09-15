package model;

public class SimulatedResult {

    private final String gameId;
    private final String date;
    private final String pickTeamId;
    private final String strategy;
    private final String betType;
    private final double oddsUsed;
    private final double sharpOdds;
    private final boolean success;
    private final double profit;
    private final double ev;

    public SimulatedResult(String gameId,
                           String date,
                           String pickTeamId,
                           String strategy,
                           String betType,
                           double oddsUsed,
                           double sharpOdds,
                           boolean success,
                           double profit,
                           double ev) {
        this.gameId = gameId;
        this.date = date;
        this.pickTeamId = pickTeamId;
        this.strategy = strategy;
        this.betType = betType;
        this.oddsUsed = oddsUsed;
        this.sharpOdds = sharpOdds;
        this.success = success;
        this.profit = profit;
        this.ev = ev;
    }

     public SimulatedResult(String gameId,
                           String date,
                           String betType,
                           double side1American,
                           double side2American,
                           double profitOrMargin) {
        this(gameId,
             date,
             null,                 
             "Arbitrage",          
             betType,
             side1American,     
             side2American,        
             true,              
             profitOrMargin,       
             profitOrMargin);     
                           }
                           
    public String  getGameId()     { return gameId; }
    public String  getDate()       { return date; }
    public String  getPickTeamId() { return pickTeamId; }
    public String  getStrategy()   { return strategy; }
    public String  getBetType()    { return betType; }
    public double  getOddsUsed()   { return oddsUsed; }
    public double  getSharpOdds()  { return sharpOdds; }
    public boolean isSuccess()     { return success; }
    public double  getProfit()     { return profit; }
    public double  getEv()         { return ev; }
}
