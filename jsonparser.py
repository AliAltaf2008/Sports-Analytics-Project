import pandas as pd

df = pd.read_csv('data/Games.csv')
df.fillna('', inplace=True)
df.to_json('resources/AllGames.json', orient='records', indent=2)
