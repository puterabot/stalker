# ME web crawler agent

This java/gradle project contains several applications for feeding the database from the ME site.

### Making a configuration file

Create an `./backend_me/src/main/application.properties` file and add the configuration lines with your database
credentials.

```
mongo.server=127.0.0.1
mongo.port=27017
mongo.user=theUser
mongo.password=thePwd
mongo.database=theDb
me.image.download.path=/some/folder/where/to/download/images
chromium.config.path=/some/folder/usually/user/.config/chromium
```

Project can be imported on a IDE development tool as such Intellij.

### Starting up the scraper engine backend

To run:

```
./gradlew -Papp=<mainClass>
```

where **mainClass** is one of the following:
- **MeCityListExtractorTool**: to be executed once per country, connects to ME site and browse regions and cities. This is useful to create or update configuration classes.
- **MeBotSeleniumApp**: default main class use, main web crawler to get post, profile and image information.
- **MeLocalDataProcessorApp**:  after having some downloaded posts, profiles and images, executes data cleanup, image processing and other analysis steps, as such face extraction and neural network trainning.
