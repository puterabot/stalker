import { Meteor } from 'meteor/meteor';

GLOBAL_properties = {};
importPropertiesFile = function() {
    const filename = '../../../../../config/appConfig.json';
    console.log('READING CONFIGURATION FILE ' + filename);
    const fs = require('fs');

    fs.readFile(filename, 'utf8', (err, data) => {
        if (err) {
          console.error('Error reading file:', err);
          GLOBAL_properties = null;
        } else {
          try {
            GLOBAL_properties = JSON.parse(data);
            console.log('GLOBAL_properties:', GLOBAL_properties);
          } catch (jsonError) {
            console.error('Error al analizar el archivo JSON:', jsonError);
          }
        }
      });
}

importPropertiesFile();
