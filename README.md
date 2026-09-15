Sports Analytics Project

A personal project analyzing NBA betting markets to evaluate market efficiency and surface statistically favorable opportunities, using real historical odds and game outcomes.

Overview

This project processes over 100,000 historical NBA betting odds records (moneyline, spread, and totals) alongside actual game results to detect pricing inefficiencies across sportsbooks. For each game, it compares a "sharp" book's odds (used as a proxy for the market's true probability estimate) against other books' odds to calculate expected value, and separately checks for arbitrage and "middle" opportunities where mismatched lines across books create risk-reducing or risk-free setups. Results are validated against actual game outcomes and exported to CSV for review.

Features
Positive EV detection — compares each book's odds against a sharp-book baseline (Pinnacle, when available) to calculate expected value for moneyline and spread bets
Arbitrage detection — scans moneyline, spread, and totals markets for two-way arbitrage opportunities across books
Middle detection — identifies spread/totals mismatches across books that create "middle" opportunities
Outcome validation — checks flagged picks against real historical game results (scores, winners) rather than simulated ones
JSON data pipeline — converts raw CSV data into JSON for processing (via jsonparser.py), parsed in Java with Jackson
CSV reporting — exports every simulation/detection run to CSV for further analysis
Tech Stack
Java — core data modeling, EV/arbitrage/middle detection logic, CSV export (src/)
Jackson (lib/) — JSON parsing/deserialization
Python (jsonparser.py) — CSV → JSON preprocessing using pandas
Data
data/ — raw historical CSVs: NBA moneyline, spread, and totals odds, plus game results (Games.csv)
resources/ — JSON versions of the above, used as input to the Java pipeline
Moneyline, spread, and totals datasets each contain 125,000+ historical odds records
Project Structure
BettingSimulation/
├── data/                   # Raw historical CSVs (odds + game results)
├── resources/              # JSON versions of the data
├── lib/                    # Jackson JARs
├── jsonparser.py           # CSV → JSON conversion
└── src/
    ├── main/Main.java
    ├── simulator/Simulator.java   # Orchestrates EV / arbitrage / middle runs
    ├── model/                     # GameDataB, SimulatedResult
    └── util/                      # PositiveEV, Arbitrage, Middle, CsvWriter
