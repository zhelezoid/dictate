# Dictate для macOS

Приложение в строке меню. Держишь `fn`, говоришь, отпускаешь, и текст вставляется в активное поле любого приложения.

| Сочетание | Что делает |
|---|---|
| `fn` | вставить как сказано |
| `fn` + `Shift` | сказать по-русски — вставить по-английски |
| `fn` + `⌥` | записать в память (ничего не вставляется) |
| `⌃⌥V` | вставить последнюю диктовку ещё раз |

Модификатор можно зажать до `fn` или после. Режим один на всю фразу.

## Установка

Нужны: Mac на Apple Silicon с macOS 14+, Xcode Command Line Tools (`xcode-select --install`), ключ xAI.

```sh
# 1. Ключ xAI — в Keychain. -w последним: security спросит ключ сам, он не попадёт в историю шелла
security add-generic-password -U -a dictate -s dictate-xai -w

# 2. Собрать и установить (~/Applications/Dictate.app + автозапуск через launchd)
./build.sh
```

3. Разрешения (один раз): **System Settings → Privacy & Security**
   - **Microphone** → Dictate — вкл (система спросит сама при первой записи);
   - **Accessibility** → `+` → `~/Applications/Dictate.app` — вкл (глобальная клавиша и вставка ⌘V);
   - **Input Monitoring** → Dictate — вкл, если клавиша не ловится.
4. **System Settings → Keyboard → «Press 🌐 key to» → «Do Nothing»**. Иначе на `fn` будет открываться панель эмодзи.
5. Модели xAI уже выбраны и прописаны — **не менять** (почему — [AGENTS.md](../AGENTS.md), «Модели»).
6. Настройки: иконка в строке меню → «Настройки и словарь…» (это `~/.config/dictate/config.json`, пример — [config.example.json](config.example.json)) → «Перечитать настройки».

**Подпись.** `build.sh` подписывает сертификатом «Apple Development». Его можно получить бесплатно: Xcode → Settings → Accounts → войти Apple ID. Без сертификата сборка подписывается ad-hoc, и тогда разрешения из шага 3 слетают после каждой пересборки. Другой сертификат: `DICTATE_SIGN_IDENTITY="…" ./build.sh`.

## Где что лежит

`~/.config/dictate/`:
- `config.json` — настройки и словари;
- `memory.md` — память;
- `history.jsonl` — история за 14 дней, права 600;
- `dictate.log` — журнал ошибок;
- `last.wav` — последняя запись, нужна для «Распознать заново».

Перезапуск: `launchctl kickstart -k gui/$(id -u)/app.dictate`. После падения приложение поднимается само.
