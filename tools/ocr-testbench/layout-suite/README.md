# Reading-order test suite

`python3 layouts.py` writes 390 synthetic pages (13 layouts: single column, 2 and 3 columns,
table, form, receipt, menu, letter, bullets, medicine label, text wrapped around a picture,
sidebar, labels on a picture) with tilt, page curl and jitter. `run.sh` compiles the app's real
`ReadingOrder.kt` and scores word order and paragraph breaks per layout. `e2e.py` renders pages
and runs the real OCR models before layout (`sc.py` scores it).
