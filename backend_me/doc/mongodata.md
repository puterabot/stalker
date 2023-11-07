# MONGO DATABASE USAGE

## Data dictionary
This system connects to a mongodb database called `mileroticos` and uses the following collections:

**post**:
  - **_id**: Mongo object id
  - **c**: country two-letter code
  - **d**: description text (can be non-existent)
  - **i**: integer id
  - **l**: location label (can be non-existent)
  - **md**: measured date from profile (can be non-existent)
  - **p**: already processed flag:
     . non-existent: pending to process
     . true: imported ok, profile and image information expected
     . false: imported with error, no profile found or profile skipped due to not having images
  - **r**: region search when first download
  - **s**: service search when first download
  - **t**: time of first list download / first published date (is updated)
  - **u**: identified user profile
     . non-existent: to process
     . ObjectId: id for a profile
     . false: not found, unlinked
  - **url**: post source
  - **w**: whatsapp promise flag (can be non-existent)

**profile**
  - **_id**: Mongo object id
  - **p**: phone
  - **s**: deep Search performed
    . Non-existent: search not tried, to do
    . false: search tried and failed with error
    . true: search performed and possible additional posts added
  - **t**: time of last update

  - **a**: alarmed (object, still not used!)
    . Non-existent or false: not notified yet
    . True: already sent to alarm system (reported as new profile)
  - **f**: manual flags (object, still not used!)
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
  - **a**: analysis info (basic sha + size)
    . non-existent: image not still processed
    . s: sha 512 sum
    . w: width
    . h: height
    . f: file size
    . note that a is present in all downloaded images
  - **af**: analysis for findimagedupes descriptors
    . non-existent: image not still processed
    . d: 32 bytes (256 bit) find image descriptor: normalized 16x16 binary image thumbnail
    . note that af is present only on parent images (those with {x: true})
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

Note that groups are made around hints. Hints can be strong or weak.
- Strong hint: it is 100% changes of having a profile pair: 1. identical image files, 2. profile changed at origin,
  3. a previously weak hint verified by hand.
- Weak hint: it is a change that this hint relates two profiles, and also a change of a mistake / false positive:
  4. file descriptor computed by findimagedupes, haar face detection, YOLO or others.
- On the UI for traversing recent profiles, strong and weak hints should be shown.

# Pending data quality checks

## Profiles without posts

There are 396 profiles, some with images, that has no post references. How is this possible?
Recommended filter: detect them, delete the associated images and remove profile and profileInfo from database.

```
db.profileInfo.find({numPosts: 0}, {_id: false, postIdArray: false, postUrlArray: false, imageIdArray: false, locationArray: false})
```

## Mismatch between stored images on folder and images on database

The number on this two queries are a little different:

```
db.image.find({x: true}).count()
```

```
find . -name "*.jpg" | wc -l
```

Perhaps the clean up process for repeated images have failed to remove some files.

Recommended filter:
- verify each existing image on file has a corresponding entry on database with x: true
- verify each x: true database entry has an existing physical image on folder
- remove all files on folder with a corresponding x: {ObjectId} reference (previous failed delete)
- report any file on folder without corresponding database entry

## Detect profiles with invalid phone numbers

This should give 0

### Remove black areas around images

- Findimagedupes is having issues on correctly identifying matches when black borders surrounds real
  image data
- It is important to detect, modify those images to remove the borders and resave shasum
  data on database for both the parent image and all of its siblings

## Images unable to download

For one reason or another, such a ME user deleting her profile just after the post download step is done
but before the post analysis step is started, system will have identified images with _id and url, but
without image data available:

´´´
db.image.find({$and: [{d: false}, {a: {$exists: false}}, {x: {$exists: false}}]}).count()
´´´

On this cases, it is needed to have an operation (on the MeLocalDataProcessorApp program) to:
- Unset d on all this images to let them in an starting state
- Retry downloading them
- For the ones where download is failing, continue
- Search for all profiles using this images and unlink them
- Delete the image not possible to be downloaded (previous query should return 0)
- Search for all profiles that after this operation remain with 0 images and delete them

## Profiles with null first and last dates

Example case: 3184863478

There are some profiles with correctly linked image data and/or post data with null dates on info
report. Should rebuild them.

```
db.profileInfo.find({firstPostDate: null}).count()
```

## External links data available on profiles

Check 3012301101. It has "Visita mi web" link to onlyfans profile.

## Deleted and updated profiles in source

It has been detected that ME users can change a phone, without deleting previously published posts. This can
be detected by browing again to the page of the post, extrating again the phone number from it and checking to
see if the phone has changed (or if the post is not available anymore).

Proposed solution is to add a hint for this case (phone change at data origin) to a profileGroup, and in the
interface for reviewing new profiles, add profile group traversal to detect group of phones.

## Deleted images

For yet undetermined reasons, there is evidence of previously existing image files being deleted.
It is important to implement a backup schema for recently downloaded images, particularly if they are
parent images:

```
db.image.find({$and: [{d: false}, {x: true}]}).count()
```

To mitigate, the following actions are pending:
- When determining parents, images that are new parents should be backed up.
- When missing images are detected, they should be marked as not downloaded, and download process should
  be restarted/retried.
- When a previously identified set of profiles contains child images that points to a missing master
  image that failed to download on the retry, the image set should be cleaned/deleted.

## Useful queries

### To list attributes on a collection

```
var distinctAttributes = new Set();
db.post.find().forEach(function(document) {
    for (var key in document) {
        distinctAttributes.add(key);
    }
});
var sortedAttributes = Array.from(distinctAttributes).sort();
printjson(sortedAttributes);
```

### Check profiles with more related profiles due to repeated images

```
db.profileInfo.find({ $expr: { $eq: [{ $size: "$relatedProfilesByReplicatedImages" }, 37] } }).count()
```

### Latest profiles

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
