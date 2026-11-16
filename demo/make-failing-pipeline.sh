#!/usr/bin/env bash
# Створює гілку з навмисною помилкою в коді (знижка постійному слухачу 8% -> 5%) і пушить її.
# Pipeline має стати червоним: впадуть unit-тести та API-тести.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git checkout -b demo/failing-pipeline
git apply demo/failing-change.patch
git commit -am "demo: intentional bug in calculateTotal (regular discount 5% instead of 8%)"
git push -u origin demo/failing-pipeline
echo "Відкрийте вкладку Actions: stage 'Unit tests' має завершитись з помилкою."
