globalProfile = new Mongo.Collection('profile');

if (Meteor.isServer) {
    console.log('  - Publishing profile collection');
    Meteor.publish('profile', function () {
        return globalProfile.find({});
    });
    globalProfile.allow({
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
