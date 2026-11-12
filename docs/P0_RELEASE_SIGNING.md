# P0-B: стабильная подпись APK (локально на marduk)

**Состояние:** готова процедура сборки; P0-B закрывается **после** реальной проверки
обновления с сохранением identity на устройстве. Никакие ключи не создаются в CI,
не передаются GitHub и не попадают в репозиторий.

## 1. Один раз создать release signing key

На доверенном `marduk`, в твоём каталоге `~/aap2` (файл должен оставаться **вне Git**, даже если сам каталог — рабочая копия репозитория):

```zsh
umask 077
mkdir -p ~/aap2
chmod 700 ~/aap2

keytool -genkeypair -storetype PKCS12 \
  -keystore ~/aap2/aap2-release.p12 \
  -alias aap2 -keyalg RSA -keysize 3072 -validity 10000

chmod 600 ~/aap2/aap2-release.p12
```

Выбери сильный пароль при запросе `keytool`; **не вписывай его в скрипт,
Gradle, shell history, переменную окружения или GitHub Secret**.

Можно прикрепить сам файл `aap2-release.p12` к записи в KeePass и сохранить там пароль. Но передавать его в GitHub или делать обычную незашифрованную резервную копию нельзя. Желательно также иметь **независимую зашифрованную офлайн-копию**: одна-единственная база KeePass — единая точка отказа. Без исходного signing key нельзя штатно
обновить APK на удалённом Android, даже если исходники проекта остались.

Если `~/aap2` сам является Git-репозиторием, убедись, что файл **не отслеживается**. В ветке P0-B добавлено правило `*.p12` в `.gitignore`, но не рассчитывай на него при работе в другой ветке или другом репозитории. Не делай `git add -f` для ключа.

Проверка сертификата (без раскрытия приватного ключа):

```zsh
keytool -list -v -keystore ~/aap2/aap2-release.p12 -alias aap2
```

## 2. Локальная сборка и подпись

Из клона нужного коммита (после слияния P0-A и P0-B):

```zsh
export AAP2_VERSION_CODE=20000
export AAP2_VERSION_NAME=2.0.0-p0b1

sh scripts/build-signed-apk.sh
```

Скрипт использует `./gradlew :app:assembleRelease` для **unsigned APK**,
затем `zipalign`, `apksigner sign` с **интерактивным вводом пароля**
и `apksigner verify --print-certs`. На выходе:

```text
app/build/outputs/apk/local-release/aap2.apk
```

Нужны JDK 17 и Android SDK Build Tools 35.0.0. По умолчанию SDK ищется в
`$ANDROID_HOME`, затем `$ANDROID_SDK_ROOT`, затем `~/Android/Sdk`.
Если Build Tools лежат в другом каталоге, задай `AAP2_BUILD_TOOLS` как
абсолютный путь к каталогу с `apksigner` и `zipalign`.

Скрипт не создаёт signing key, не вводит автоматический fallback на debug key
и не размещает release APK в GitHub Actions.

Сохрани первый подписанный APK для проверки:

```zsh
mkdir -p ~/aap2-releases
cp app/build/outputs/apk/local-release/aap2.apk ~/aap2-releases/aap2-20000.apk
```

В выводе `apksigner verify --print-certs` записать
`Signer #1 certificate SHA-256 digest` — **публичный** отпечаток сертификата.

## 3. Собрать обновление тем же signing key

```zsh
export AAP2_VERSION_CODE=20001
export AAP2_VERSION_NAME=2.0.0-p0b2

sh scripts/build-signed-apk.sh
cp app/build/outputs/apk/local-release/aap2.apk ~/aap2-releases/aap2-20001.apk
```

В обеих сборках должен быть **один и тот же certificate SHA-256**,
а `versionCode` во второй должен быть больше.

## 4. Реальная проверка установки

**ВНИМАНИЕ:** текущая P0-A сборка на Zebra подписана временным debug key
GitHub Actions. Первый переход debug → постоянная release подпись
**НЕ является обновлением**: `adb install -r` вернёт
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Нужен **однократный** uninstall
**только AAP2** (не AAP1), с потерей текущих Keystore identity и test profile.
Не делай этот переход, пока нужны текущие P0-A Wi-Fi/negative тесты.

Для отдельного тестового телефона или после окончания P0-A испытаний:

```zsh
adb uninstall net.isaeff.android.asproxy2.test
adb uninstall net.isaeff.android.asproxy2
adb install ~/aap2-releases/aap2-20000.apk

adb install -r ~/aap2-releases/aap2-20001.apk
```

Повторный uninstall **между** двумя release сборками запрещён:
это уничтожило бы сам смысл проверки. Убедись, что Android завершает
`adb install -r` со статусом `Success` и не требует очистки данных.

**Подтверждение сохранности Keystore и pinned host key:** этот пункт нужно
проверить отдельно на release-сборке с тестовым enrollment/profile.
Сейчас AAP2 в обычном UI ещё не включает Keystore provisioning; P0-A тест
делается с отдельным debug instrumentation APK. Сам по себе успешный
`adb install -r` доказывает корректную подпись и обновляемость, но
**не доказывает** сохранность SSH identity. Не отмечай P0-B полностью до
этой проверки.

## Что не делать

- Не копировать `*.p12` / `*.jks` в GitHub, Actions secrets или artifacts.
- Не подписывать обычный релиз временным ключом CI.
- Не считать `app-release-unsigned.apk` выпуском.
- Не менять signing certificate между релизами.
- Не публиковать APK до проверки подписи на реальном устройстве.

Источник по порядку операций: Android Developers,
https://developer.android.com/build/building-cmdline и
https://developer.android.com/tools/apksigner
