import { Meteor } from 'meteor/meteor';
import { Router } from 'meteor/iron:router';
import { ReactiveVar } from 'meteor/reactive-var';
import { Template } from 'meteor/templating';
import { HTMLElement } from 'dom';

Router.route('/validateIncomingProfiles', {
    template: 'validateIncomingProfilesTemplate',
    data: function () {
        return Meteor.subscribe("profileInfo_latest", { object: "tuntun" });
    }
});

const selectedProfileIndex = new ReactiveVar(0);
const selectedProfile = new ReactiveVar(null);
const profileDataset = new ReactiveVar(null);
let profileInfoArray: Array<any> | null = null;

const compareByFirstPostDate = function (a, b) {
    return b.firstPostDate - a.firstPostDate;
}

const compareByMd = function (a, b) {
    return b.md - a.md;
}

Template.validateIncomingProfilesTemplate.helpers({
    profileInfo: function () {
        profileInfoArray = globalProfileInfo.find().fetch();
        if (!profileInfoArray) {
            return [];
        }
        profileInfoArray.sort(compareByFirstPostDate);
        if (profileInfoArray && profileInfoArray.length > 0 &&
            selectedProfile.get()) {
            Meteor.call('getProfileDataset',
                selectedProfile.get()._id, function (e, r) {
                    if (!e) {
                        if (r['error']) {
                            alert(r.error);
                        } else if (!r.images) {
                            alert('ERROR: Profile has no images');
                        } else {
                            profileDataset.set(r);
                            r.images.sort(compareByMd);
                            computeImagePreviewSizes(r.images);
                            selectedImage.set(r.images[0]);
                        }
                    }
                });
        }
        return profileInfoArray;
    },
    selectedProfileIndex: function () {
        return selectedProfileIndex.get() + 1;
    },
    selectedProfileInfo: function () {
        return selectedProfile.get();
    },
    profileDataset: function () {
        return profileDataset.get();
    },
    selectedImage: function () {
        return selectedImage.get();
    },
    selectedImageDateFormatted: function () {
        const m = {
            0: 'Jan',
            1: 'Feb',
            2: 'Mar',
            3: 'Apr',
            4: 'May',
            5: 'Jun',
            6: 'Jul',
            7: 'Ago',
            8: 'Sep',
            9: 'Oct',
            10: 'Nov',
            11: 'Dec'
        };
        if (selectedImage.get() == null || selectedImage.get()['md'] == null) {
            return 'LOADING...';
        }
        const d = selectedImage.get().md;
        return '' + m[d.getMonth()] + ' ' + d.getDate() + ' ' + d.getFullYear();
    }
});

const goNext = function () {
    if (!profileInfoArray) {
        return null;
    }
    let v = selectedProfileIndex.get();
    v++;
    if (v >= profileInfoArray.length) {
        v = profileInfoArray.length - 1;
    }
    selectedProfileIndex.set(v);
    selectedProfile.set(profileInfoArray[v]);
}

const goPrev = function () {
    if (!profileInfoArray) {
        return null;
    }
    let v = selectedProfileIndex.get();
    v--;
    if (v < 0) {
        v = 0;
    }
    selectedProfileIndex.set(v);
    selectedProfile.set(profileInfoArray[v]);
}

const handleKeyDownCallback = function (e) {
    switch (e.key) {
        case 'ArrowRight':
            goNext();
            break;
        case 'ArrowLeft':
            goPrev();
            break;
    }
}

Template.validateIncomingProfilesTemplate.onRendered(function () {
    const e = document.getElementsByTagName('body')[0];
    e.onkeydown = handleKeyDownCallback;
    Meteor.setTimeout(function () {
        const container: HTMLElement = document.getElementsByClassName('rightcol')[0];
        container.style.width = (window.innerWidth - 640 - 40) + 'px';
        container.style.height = '100vh';
        if (profileInfoArray) {
            selectedProfile.set(profileInfoArray[0]);
        }
    }, 400);
});

Template.validateIncomingProfilesTemplate.events({
    "mouseenter .previewImage": function (e) {
        const profile = profileDataset.get();
        selectImageFromId(profile, e.target.id);
    },
    "click .previewImage": function (e) {
        // Used on mobile where there is no mouse hover / enter / exit
        const profile = profileDataset.get();
        selectImageFromId(profile, e.target.id);
    },
    "click #nextButton": function (e) {
        goNext();
    },
    "click #prevButton": function (e) {
        goPrev();
    }
});
