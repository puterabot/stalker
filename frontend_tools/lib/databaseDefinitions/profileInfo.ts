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
        return globalProfileInfo.find(filter);
    });
    Meteor.publish('profileInfo_maxImages', function () {
        const filter = {$and: [
            {firstPostDate: {$ne: null}},
            //{lastLocation: /bogot/i},
            {lastService: {$not: /gay/}},
            {lastService: {$not: /gigolo/}},
            {lastService: {$not: /travesti/}},
            {lastService: {$ne: "servicios-virtuales"}},
            {numImages: {$gte: 1000}}
        ]};
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
