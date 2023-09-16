globalImage = new Mongo.Collection('image');

if (Meteor.isServer) {
    console.log('  - Publishing image collection');
    Meteor.publish('image', function () {
        return globalImage.find({});
    });
    globalImage.allow({
        insert: function (userId, doc) {
            return true;
        },
        update: function (userId, doc, fields, modifier) {
            return true;
        },
        remove: function (userId, doc) {
            return true;
        }
    });
}
