# ME web crawler agent

This java/gradle project contains several applications for feeding the database from the ME site.

To run:

```
./gradlew -Papp=<mainClass>
```

where **mainClass** is one of the following:
- **MeCityListExtractorTool**: to be executed once per country, connects to ME site and browse regions and cities. This is useful to create or update configuration classes.
- **MeBotSeleniumApp**: default main class use, main web crawler to get post, profile and image information.
