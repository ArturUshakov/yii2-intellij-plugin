# Yii2Storm

Production-ready плагин для PhpStorm с поддержкой Yii2 Framework.

## Архитектура

```
com.yii2storm/
├── context/          # Определение Yii2 контекстов
├── resolver/         # Единые сервисы резолва
├── reference/        # Reference contributors
├── completion/       # Completion contributors
├── inspection/       # Inspections и quick fixes
├── action/           # Actions
└── util/             # Утилиты
```

## Функциональность

| Фича | Описание |
|------|----------|
| **View** | Ctrl+Click на `render()` → view, автодополнение, inspection + quick fix |
| **Route** | Ctrl+Click на `['site/index']` → action, автодополнение routes |
| **Translation** | Ctrl+Click на `Yii::t()` → определение, автодополнение категорий/ключей |
| **Alias** | Ctrl+Click на `@app/...` → директория, автодополнение, inspection |
| **Component** | Автодополнение `Yii::$app->` (cache, db, user, ...) |

## Установка для разработки

1. Откройте проект в IntelliJ IDEA
2. Настройте SDK: IntelliJ Platform Plugin SDK
3. Запустите через Gradle: `./gradlew runIde`

## Сборка плагина

```bash
./gradlew buildPlugin
```

Плагин соберётся в `build/distributions/yii2-storm-1.0.0.zip`

## Установка в PhpStorm

1. Settings → Plugins → Install from disk
2. Выберите `yii2-storm-1.0.0.zip`
3. Перезапустите IDE

## Требования

- PhpStorm 2023.2+
- JDK 17+
