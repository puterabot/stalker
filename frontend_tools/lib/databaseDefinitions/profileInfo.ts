import { Meteor } from 'meteor/meteor';
import { Mongo } from 'meteor/mongo';

globalProfileInfo = new Mongo.Collection('profileInfo');

if (Meteor.isServer) {
    console.log('  - Publishing profileInfo collection');
    Meteor.publish('profileInfo_latest', function (param) {
        
        const filter = {$and: [
            {firstPostDate: {$ne: null}},
            {lastLocation: /bog/i},
            
            //{lastService: "travestis"},
            {lastService: {$not: /travesti/}},
            {lastService: {$not: /gay/}},
            {lastService: {$not: /gigolo/}},
            {lastService: {$ne: "servicios-virtuales"}},
            
            {lastPostDate: {$gte: new Date("2023-11-01T00:00:00.000Z")}},
            {firstPostDate: {$gte: new Date("2023-10-21T00:00:00.000Z")}}
        ]};
        
        //const filter = { $expr: { $gt: [{ $size: "$imageIdArray" }, 2000] } };
        //const filter = { $expr: { $eq: [{ $size: "$relatedProfilesByReplicatedImages" }, 37] } };
        return globalProfileInfo.find(filter);
    });
    globalProfileInfo.allow({
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
