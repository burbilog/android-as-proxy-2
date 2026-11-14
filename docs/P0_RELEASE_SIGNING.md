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

## 4. Реальная проверка на ЗАПАСНОМ Android

**Рабочую Zebra не трогать.** Переход с CI debug APK на постоянную
release-подпись несовместим с обычным `adb install -r`; uninstall
уничтожил бы её Keystore identity и профиль. Тестировать надо на отдельном
физическом Android, где ещё **нет AAP2**. AAP1 не затрагивается.

Проверку двух локально собранных APK делай из этого checkout A1:

```zsh
OLD=~/aap2-releases/aap2-20000.apk
NEW=~/aap2-releases/aap2-20001.apk

sh scripts/aap2-spare-upgrade.sh check "$OLD" "$NEW"
```

`check` проверяет Android applicationId, подпись обеих APK, **одинаковый**
SHA256 сертификата и увеличение versionCode; откажет для debuggable APK.
Это **не доказывает**, что использован именно твой постоянный ключ:
сравни выведенный сертификат с локальным `keytool -list -v` для
`~/aap2/aap2-release.p12`. Пароль вводится интерактивно; не передавай
его в команды, GitHub или логи.

Когда подключен запасной телефон, возьми его serial из `adb devices -l`,
а затем выполни:

```zsh
export AAP2_SPARE_SERIAL='СЕРИЙНЫЙ_НОМЕР_ЗАПАСНОГО_ТЕЛЕФОНА'

sh scripts/aap2-spare-upgrade.sh first "$OLD" "$NEW"
```

Скрипт покажет serial и модель, потребует ввести `SPARE <serial>`.
Команда `first` откажет, если AAP2 уже установлен, и **никогда**
не вызывает uninstall, `pm clear` или сброс Keystore.

**Между двумя установками** зарегистрируй AAP2 через обычный UI на
отдельном тестовом сервере S2, одобри ключ и подтверди SOCKS5 HTTP.
Запиши публичный fingerprint клиентского SSH-ключа на сервере, host pin
и назначенный remote port.

Только после успешной регистрации:

```zsh
sh scripts/aap2-spare-upgrade.sh upgrade "$OLD" "$NEW"
```

`upgrade` требует установленную исходную версию и явное подтверждение
того же serial, затем делает исключительно `adb install -r`. Он
не создаёт/удаляет SSH-аккаунты, не знает PIN и не меняет Android
Keystore. Если подпись несовместима — установка завершится ошибкой,
**без попытки uninstall**.

**Подтверждение сохранности Keystore и pinned host key в A1:**
одного успешного `adb install -r` недостаточно. На **отдельном тестовом
Android** установи первую release-сборку `20000`, а затем **до обновления**
выполни настоящий A1 enrollment через обычный Compose UI на изолированный
S2-тестовый сервер с временным приглашением и одобрением ключа.

Проверь реальный SOCKS5-трафик и запиши публичный отпечаток клиентского
SSH-ключа (`ssh-keygen -lf` на сервере) вместе с fingerprint хост-ключа
S2 и назначенным портом. Затем без uninstall/очистки данных установи
release-сборку `20001` через `adb install -r`.

После обновления **без повторного enrollment и выдачи нового PIN**
должны работать тот же SSH publickey login, тот же разрешённый `-R` и
SOCKS5 HTTP. Убедись, что сервер по-прежнему видит **тот же публичный
клиентский ключ**, и что Android сохранил host pin. Отдельно проверь
холодный reboot до первой разблокировки и подключение после восстановления
Wi-Fi. Не выполняй эти действия с рабочей Zebra в ходе soak-мониторинга.

В CI уже проверен тот же механизм обновления APK на Android 11 с
**временной debug-подписью** и сохранением Keystore/host pin, но это не
заменяет испытания с постоянным release-сертификатом и физическим
устройством. Долгосрочный signing key остаётся только на `marduk`.
Не отмечай P0-B/A1 release-signing gate закрытым до физического теста.

## Что не делать

- Не копировать `*.p12` / `*.jks` в GitHub, Actions secrets или artifacts.
- Не подписывать обычный релиз временным ключом CI.
- Не считать `app-release-unsigned.apk` выпуском.
- Не менять signing certificate между релизами.
- Не публиковать APK до проверки подписи на реальном устройстве.

Источник по порядку операций: Android Developers,
https://developer.android.com/build/building-cmdline и
https://developer.android.com/tools/apksigner
