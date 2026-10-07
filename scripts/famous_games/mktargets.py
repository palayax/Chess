# Part of the famous-games curation tools (G1, docs/FAMOUS_GAMES.md section 4).
import json
import sys
from wk import category

cats = [("de", "Kategorie:Schachpartie"), ("ru", "Категория:Шахматные партии"), ("es", "Categoría:Partidas de ajedrez"),
        ("it", "Categoria:Partite di scacchi"), ("nl", "Categorie:Schaakpartij"), ("pl", "Kategoria:Partie szachowe"),
        ("en", "Category:Chess games")]
targets = []
for lang, cat in cats:
    for t in category(lang, cat):
        if t.startswith(("Kategorie:", "Category:", "Категория:", "Categoría:", "Categoria:", "Categorie:", "Kategoria:")):
            continue
        per = "/Partien" in t or t.startswith("Liste") or t.startswith("List of")
        targets.append([lang, t, per])
years = [1886, 1889, 1890, 1892, 1894, 1896, 1907, 1908, 1910, 1921, 1927, 1929, 1934, 1935, 1937, 1948, 1951, 1954,
         1957, 1958, 1960, 1961, 1963, 1966, 1969, 1972, 1974, 1975, 1978, 1981, 1984, 1985, 1986, 1987, 1990, 1993,
         1995, 1996, 1998, 1999, 2000, 2004, 2006, 2007, 2008, 2010, 2012, 2013, 2014, 2016, 2018, 2021, 2023, 2024]
for y in years:
    targets.append(["en", f"World Chess Championship {y}", True])
json.dump(targets, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=0)
print(len(targets))
