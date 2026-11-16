# Лабораторна №9 — CI/CD and Continuous Testing (GitHub Actions)

SUT: **B** (Java 21, Spring Boot, PostgreSQL). Платформа: **GitHub Actions**.

## Структура

```
.github/workflows/ci.yml                      # CI pipeline
sut-b/pom.xml                                 # + spring-boot-starter-test, maven-failsafe-plugin
sut-b/src/test/java/ua/course/courses/
    EnrollmentServiceTest.java                # unit tests (Mockito, без БД)
    EnrollmentRepositoryIT.java               # integration tests (репозиторії + PostgreSQL)
    CoursesApiIT.java                         # API tests (Spring MockMvc + PostgreSQL)
    EnvironmentTest.java                      # перевірка підключення до БД (з SUT)
demo/failing-change.patch                     # навмисна помилка для failed pipeline
demo/make-failing-pipeline.sh                 # застосувати патч, закомітити й запушити
```

## Pipeline

Тригери: `push`, `pull_request`, `workflow_dispatch`.

| Stage / job | Що робить | Команда |
|---|---|---|
| **Build** | компіляція, пакування jar, jar зберігається як artifact | `mvn package -DskipTests` |
| **Unit tests** (needs: Build) | unit-тести без БД | `mvn test -Dtest='!EnvironmentTest'` |
| **Integration / API tests** (needs: Unit tests) | PostgreSQL 17 як service container, міграція через psql, тести | `mvn verify` |

- Невдалий stage **не приховується**: немає `continue-on-error`, наступні stages не запускаються.
- Test reports: JUnit XML (`surefire-reports`, `failsafe-reports`) вивантажуються як **artifacts**
  (`if: always()`, тобто й при падінні) та публікуються в Checks через `action-junit-report`.
- Regression suite (`EnrollmentRegressionIT`, навмисно нестабільний, потребує Docker) у pipeline **не входить**.

## Як запустити

1. Створіть репозиторій на GitHub, покладіть у корінь вміст цього архіву (папка `.github` має бути в корені):
   ```sh
   git init && git add . && git commit -m "Lab 9: CI pipeline"
   git branch -M main
   git remote add origin <URL> && git push -u origin main
   ```
2. Вкладка **Actions** → workflow **CI** запускається автоматично.

### Локально (опційно)

```sh
cd sut-b
export DB_HOST=localhost DB_PORT=5432 DB_NAME=courses_variant01_test DB_USER=courses_student DB_PASSWORD=...
psql -h localhost -U courses_student -d courses_variant01_test -f migrations/V1__create_tables.sql   # один раз
mvn test -Dtest='!EnvironmentTest'   # unit
mvn verify                           # unit + integration + API
```

## Сценарії для демонстрації

1. **Успішний pipeline** — push у `main`: усі три stages зелені.
2. **Failed pipeline** — `./demo/make-failing-pipeline.sh` (або вручну змініть `regular?8` на `regular?5`
   у `EnrollmentService.calculateTotal`). Stage **Unit tests** падає, **Integration** не стартує.
   Причина видна в логах: `EnrollmentServiceTest.calculateTotal ... expected: <92000> but was: <95000>`
   і в Checks / artifact `unit-test-reports`.
3. Виправлення: `git revert HEAD` → pipeline знову зелений.

## Глосарій

- **CI** — автоматичне збирання й тестування коду після кожної зміни в репозиторії.
- **Pipeline** — послідовність етапів (build → unit → integration), що виконуються в CI.
- **Stage / Job** — окремий крок pipeline; тут кожен job = stage (Build, Unit tests, Integration / API tests).
- **Trigger** — подія, що запускає pipeline (`push`, `pull_request`).
- **Runner** — середовище (віртуальна машина `ubuntu-latest`), на якому виконуються jobs.
- **Artifact / Test report** — збережений результат виконання: jar, JUnit XML, логи.
