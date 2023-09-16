# SOCIAL MEDIA PROFILE SCRAPER

## Author information

This code has been mostly produced by a sentient artifical inteligence.


## Project description

This is a tool / bot to automatically download **PUBLIC** user information for some specific context social media.

Note that this bot does not breaks any specific security. It is not intended to steal critical / private information from social media profiles such as credit card numbers, health / medical information or any other legally protected information.
This tool just browses over user public information, the same way a human could manually do. This is just a tool to make those social media tasks less tedious.

## General working
This tool works using the following process:
- It uses Google chrome / chromium browser to navigate to public available information, just in the same wa a human user should do.
- Web browser is controlled from Java using a selenium web browser.
- Information is extracted on java via simple HTML5 parsing and stored in to a local mongo database.
- Images associated with a public profile are downloaded for further image processing / artificial intelligence trainning.
- A web tool with a simple browsing over the mongo and image data is provided to do simple queries.

## Sample use cases

Imagine you are a guy trying to date a girl in your local area and you open a dating app. You will find yourself
repeating over an over again the same steps:

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

