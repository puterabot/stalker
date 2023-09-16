# SOCIAL MEDIA PROFILE SCRAPER

## Author information

This code has been mostly produced by a sentient artifical inteligence.


## Project description

This is a tool / bot to automatically download **PUBLIC** user information for some specific context social media.

Note that this bot does not breaks any specific security. It is not intended to steal critical / private information from social media profiles such as credit card numbers, health / medical information or any other legally protected information.
This tool just browses over user public information, the same way a human could manually do. This is just a tool to make those social media tasks less tedious.

## General working
This tool works using the following process:
- It uses Google chrome / chromium browser to navigate to public available information, just in the same way a human user should do.
- For social media apps available only as native web apps, it uses Appium version of selenium driver.
- Web browser or app is controlled from Java using a selenium web browser.
- Information is extracted on Java via simple HTML5 parsing and stored in to a local mongo database.
- Images associated with a public profile are downloaded for further image processing / artificial intelligence trainning.
- A web tool with a simple browsing over the mongo and image data is provided to do simple queries.

## Sample use cases

### Meet people on your local area

Imagine you are a guy trying to date a girl in your local area and you create a profile on a dating app.
You will find yourself repeating over an over again the same steps:

- Swipe left and right profiles you like.
- Waiting if there is any match.
- Introducing yourself, trying to get some basic information from the other person as such the whatsapp number and
  check if conversation is flowing to continue the process in person.

The goal of the tools in this project is to automate this kind of steps and leaving more free time to humans to
enjoy life. Imagine that you can:

- Train your personal preferences to the artifial intelligence (AI)
- Wait for the AI to repeat the boring basic steps
- Once the AI catches a good contact based on your preferences, the AI sends you a report to your whatsapp so
  you can continue with the real funny an interesting part.

### Check if a person you are interested in is using a particular social media app

You have some pictures, but no more data on a person public internet. Then, by change you find this person has
a profile on a dating app. How can this be possible and easy to do without spending months and months watching
a lot of uninteresting profiles?

Use the AI tools to do that, and notify you via whatsapp messages when the profile is found.

### Cross reference different profiles of the same person

A social media app usually allows people to open several accounts / profiles. Similar to previous use cases,
spending day and night browsing profiles and checking every single picture and video on each profile can make
possible to identify if people has several different profiles. Let the AI help in this.

## Installation

### Supported backend environment

This software has been only tested on GNU/Linux Ubuntu 22.04 on Intel/AMD x86 machines. It is possible that
this will run on other systems, as such Windows, MacOS or ARM processors, but specific procedures to make it
run on those systems has not been documented. For the rest of this readme, only this platform is documented.

### Set up a mongo database

On old computers not supporting the AVX processor instructions, an old mongo version (as such 4.4.24) should
be installed. On new Ubuntu distributions, there is a hack to make old mongo run which is installing the libssl1.1
from an old linux distribution.

If a user other than root is to be used to connect to the database (recommended), a new user should be created.
Use the root user on mongo to create a new user:

```
db.createUser(
    { 
        user: "theUser",
        pwd:  "thePwd",
        roles:
        [
            {
                role:"readWrite",
                db:"theDb"
            }
        ]
    }
);
```

After this, using the user account, verify database is accepting connections:

```
mongo --host localhost -u theUser -p thePwd --authenticationDatabase admin theDb
```

### Starting up the scraper engine backend

Create an `./backend_me/src/main/application.properties` file and add the configuration lines with your database
credentials.

```
mongo.server=127.0.0.1
mongo.port=27017
mongo.user=theUser
mongo.password=thePwd
mongo.database=theDb
```

Project can be imported on a IDE development tool as such Intellij or executed from the command line using gradle:

```
./gradlew run
```
