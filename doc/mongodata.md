# MONGO DATABASE USAGE

## Data dictionary
This system connects to a mongodb database called `mileroticos` and uses the following collections:

**post**:
  - **url**: post source
  - **i**: integer id
  - **t**: time of first list download / first published date (is updated)
  - **md**: measured date from profile
  - **c**: country two-letter code
  - **s**: service search when first download
  - **r**: region search when first download
  - **p**: already processed flag:
     . non-existent: pending to process
     . true: imported ok, profile and image information expected
     . false: imported with error, no profile found or profile skipped due to not having images
  - **d**: description text (can be non-existent)
  - **u**: identified user profile
     . non-existent: to process
     . ObjectId: id for a profile
     . false: not found, unlinked
  - **l**: location label
  - **w**: whatsapp promise flag
  - **v**: verification, second pass
     . non-existent: second pass still not performed
     . false: not available at second pass date
     . true: revisited (used to define group when linked number is changed)

**profile**
  - **p**: phone
  - **t**: time of last update
  - **s**: deep Search performed
    . Non-existent: search not tried, to do
    . false: search tried and failed with error
    . true: search performed and possible additional posts added
  - **a**: alarmed
    . Non-existent or false: not notified yet
    . True: already sent to alarm system
  - **f**: manual flags (object)
    . reviewed
    . genderMale
    . genderFemale
    . genderTrans
    . bodyTypeFat
    . beautyGood
    . beautyBad
    . beautyFace
    . ageOld
    . ageYoung
    . colorBlack
    . colorWhite
    . colorMedium
    . wordGay
    . whatsappActive
    . agency
    . planSmallTatoo
    . planBigTatoo
    . planPregnant
    . planFree
    . planSquirt
    . planSwingerCuckold
    . wow (candidate to contact)
  - **g**: group
    . Non-existent: group with 1 profile only: this one
    . true: parent/reference in its group
    . false: child on its group
  - **gid**: group ObjectId reference

**group**
  - **c**: children array, ordered by last update date (first one is parent/reference)

**image**
  - **url**: url
  - **u**: parent profile id (user)
  - **p[]**: parent posts ids
  - **on:** ?
  - **d**: downloaded flag
    . non-existent: pending to download
    . true: download successful
    . false: error downloading (i.e. deleted when downloading)
  - **a**: analysis info
    . non-existent: image not still processed
    . s: sha 512 sum
    . w: width
    . h: height
    . f: file size
  - **md**: measured date from url
  - **x**: eXternal reference to proXy image (identical image older than this one)
    . non-existent: non grouped image
    . true: parent / older image in a set
    . ObjectId("..."): reference to parent image in a set (file removed from filesystem)

**profileInfo**
  - **p**: phone
  - **firstPostDate**: ?
  - **lastPostDate**: ?
  - **numPosts**: ?
  - **postIdArray**: ?
  - **postUrlArray**: ?
  - **lastLocation**: ?
  - **lastService**: ?
  - **locationArray**: ?
  - **firstImageDate**: ?
  - **lastImageDate**: ?
  - **numImages**: ?
  - **imageIdArray**: ?
  - **flags**: ?

# Sample queries

To obtain the latest profiles at Bogotá:
```
db.profileInfo.find({$and: [{firstPostDate: {$ne: null}}, {lastLocation: /bogota/i}, {lastService: {$not: /gay/}}, {lastService: {$not: /gigolo/}}, {lastService: {$ne: "servicios-virtuales"}}]}).sort({firstPostDate: -1})
db.getCollection('profileInfo').find({$and: [{firstPostDate: {$ne: null}}, {lastLocation: /bogot/i}, {lastService: {$not: /gay/}}, {lastService: {$not: /gigolo/}}, {lastService: {$not: /travesti/}}, {lastService: {$ne: "servicios-virtuales"}}, {lastPostDate: {$gte: new Date("2020-06-01T00:00:00.000Z")}}, {firstPostDate: {$gte: new Date("2020-05-01T00:00:00.000Z")}}]}).sort({firstPostDate: -1})
```

Example of profiles with a lot of posts:

    5ec95b8c837bc06021725e4f (541) : and this uses several profiles
    5ec95e38f75b2b407c68c279
    5ec7a750f7aa7031162c5845

Profile that have changed number (old posts published with a number still available, but now linked to another number):

    5ee94e6d286cae0736e6b7e3 - 5ec80586f7aa7031162cc7d4
