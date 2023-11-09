# A customized db_dump program from Berkeley db-5.3

The Berkeley db (last common version is 5.3 from year 2015) is a popular in-file simple database
that is being used by the `findimagedupes` utility to store image descriptors, which are 32 byte
(256 bit) binary arrays.

The way ME backend imports the descriptors after calling `findimagedupes` over a set of images is
to open the resulting database and extracting binary records. On this process, there is a problem
when using `db_dump -d a <databas_file>`: not all binary descriptors can be imported, since there
is an ambiguous printing algorithm in place:
- If all binary bytes in the descriptor are ASCII-printable, the descriptor is shown as an ASCII
  string.
- If less than a certain percentage of bytes are not ASCII-printable, they are replaced by escape
  sequences.
- If more than a certain percentage of bytes ar not ASCII-printable, they are printed as hexagesimal
  nibble data format.

For the image description application, the third option is preferable, and the first one is supported,
but the second one is ambiguous, since it is ok for just showing the descriptor on a screen, but not
suitable for importing it on another program. Note the following example:

```
\03f is \03 followed by 'f'? or is \0 followed by "3f".
```

Since this problem makes impossible to use the original `db_dump` utility, this source tree contains
a modified version of the utility called `db_dump_custom` that forces the second printing case to be
replaced by the third, thus avoiding the ambiguous impressions.

# Utility build

To compile the utility, follow the commands:

```
mkdir build
cd build
cmake ..
make
```

this will generate the `db_dump_custom` needed by `backend_me`.
