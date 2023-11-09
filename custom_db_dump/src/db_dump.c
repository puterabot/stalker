#include "db_config.h"
#include "db_int.h"
#include "dbinc/db_page.h"

int	 db_init __P((DB_ENV *, char *, int, u_int32_t, int *));
int	 main __P((int, char *[]));
int	 version_check __P((void));

extern int __db_dumptree_custom(DB *dbp, DB_TXN *txn, char *op, char *name, db_pgno_t first, db_pgno_t last);

const char *progname;

int
main(int argc, char *argv[])
{
	extern char *optarg;
	extern int optind;
	DB_ENV	*dbenv;
	DB *dbp;
	db_pgno_t first, last;
	u_int32_t cache;
	int ch;
	int exitval, mflag, private;
	int ret, rflag, resize;
	char *data_len, *dbname, *dopt, *filename, *home, *passwd;

	if ((progname = __db_rpath(argv[0])) == NULL)
		progname = argv[0];
	else
		++progname;

	if ((ret = version_check()) != 0)
		return (ret);

	dbenv = NULL;
	dbp = NULL;
	exitval = mflag = rflag = 0;
	first = last = PGNO_INVALID;
	cache = MEGABYTE;
	private = 0;
	data_len = dbname = dopt = filename = home = passwd = NULL;
	while ((ch = getopt(argc, argv, "d:D:f:F:h:klL:m:NpP:rRs:V")) != EOF)
		switch (ch) {
		case 'd':
			dopt = optarg;
			break;
		default:
            break;
		}
	argc -= optind;
	argv += optind;

	/*
	 * A file name must be specified, unless we're looking for an in-memory
	 * db,  in which case it must not.
	 */
	if (argc == 1 && !mflag) {
        filename = argv[0];
    } else {
        return 1;
    }

	/* Handle possible interruptions. */
	__db_util_siginit();

	/*
	 * Create an environment object and initialize it for error
	 * reporting.
	 */
retry:	if ((ret = db_env_create(&dbenv, 0)) != 0) {
		fprintf(stderr,
		    "%s: db_env_create: %s\n", progname, db_strerror(ret));
		goto err;
	}

	dbenv->set_errfile(dbenv, stderr);
	dbenv->set_errpfx(dbenv, progname);
	if (data_len != NULL)
		(void)dbenv->set_data_len(dbenv, (u_int32_t)atol(data_len));

	/* Initialize the environment. */
	if (db_init(dbenv, home, rflag, cache, &private) != 0)
		goto err;

	/* Create the DB object and open the file. */
	if ((ret = db_create(&dbp, dbenv, 0)) != 0) {
		dbenv->err(dbenv, ret, "db_create");
		goto err;
	}

	if ((ret = dbp->open(dbp, NULL,
	    filename, dbname, DB_UNKNOWN, DB_RDWRMASTER|DB_RDONLY, 0)) != 0) {
		dbp->err(dbp, ret, DB_STR_A("5115", "open: %s", "%s"),
		    filename == NULL ? dbname : filename);
		goto err;
	}
	if (private != 0) {
		if ((ret = __db_util_cache(dbp, &cache, &resize)) != 0)
			goto err;
		if (resize) {
			(void)dbp->close(dbp, 0);
			dbp = NULL;

			(void)dbenv->close(dbenv, 0);
			dbenv = NULL;
			goto retry;
		}
	}

	if (dopt != NULL) {
        ret = __db_dumptree_custom(dbp, NULL, dopt, NULL, first, last);
		if (ret != 0) {
			dbp->err(dbp, ret, "__db_dumptree: %s", filename);
			goto err;
		}
	}

	if (0) {
err:		exitval = 1;
	}
done:	if (dbp != NULL && (ret = dbp->close(dbp, 0)) != 0) {
		exitval = 1;
		dbenv->err(dbenv, ret, DB_STR("5117", "close"));
	}
	if (dbenv != NULL && (ret = dbenv->close(dbenv, 0)) != 0) {
		exitval = 1;
		fprintf(stderr,
		    "%s: dbenv->close: %s\n", progname, db_strerror(ret));
	}

	if (passwd != NULL)
		free(passwd);

	/* Resend any caught signal. */
	__db_util_sigresend();

	return (exitval == 0 ? EXIT_SUCCESS : EXIT_FAILURE);
}

int
db_init(dbenv, home, is_salvage, cache, is_privatep)
	DB_ENV *dbenv;
	char *home;
	int is_salvage;
	u_int32_t cache;
	int *is_privatep;
{
	int ret;

	/*
	 * Try and use the underlying environment when opening a database.
	 * We wish to use the buffer pool so our information is as up-to-date
	 * as possible, even if the mpool cache hasn't been flushed.
	 *
	 * If we are not doing a salvage, we want to join the environment;
	 * if a locking system is present, this will let us use it and be
	 * safe to run concurrently with other threads of control.  (We never
	 * need to use transactions explicitly, as we're read-only.)  Note
	 * that in CDB, too, this will configure our environment
	 * appropriately, and our cursors will (correctly) do locking as CDB
	 * read cursors.
	 *
	 * If we are doing a salvage, the verification code will protest
	 * if we initialize transactions, logging, or locking;  do an
	 * explicit DB_INIT_MPOOL to try to join any existing environment
	 * before we create our own.
	 */
	*is_privatep = 0;
	if ((ret = dbenv->open(dbenv, home,
	    DB_USE_ENVIRON | (is_salvage ? DB_INIT_MPOOL : 0), 0)) == 0)
		return (0);
	if (ret == DB_VERSION_MISMATCH || ret == DB_REP_LOCKOUT)
		goto err;

	/*
	 * An environment is required because we may be trying to look at
	 * databases in directories other than the current one.  We could
	 * avoid using an environment iff the -h option wasn't specified,
	 * but that seems like more work than it's worth.
	 *
	 * No environment exists (or, at least no environment that includes
	 * an mpool region exists).  Create one, but make it private so that
	 * no files are actually created.
	 */
	*is_privatep = 1;
	if ((ret = dbenv->set_cachesize(dbenv, 0, cache, 1)) == 0 &&
	    (ret = dbenv->open(dbenv, home,
	    DB_CREATE | DB_INIT_MPOOL | DB_PRIVATE | DB_USE_ENVIRON, 0)) == 0)
		return (0);

	/* An environment is required. */
err:	dbenv->err(dbenv, ret, "DB_ENV->open");
	return (1);
}

int
version_check() {
	int v_major, v_minor, v_patch;

	db_version(&v_major, &v_minor, &v_patch);
	if (v_major != DB_VERSION_MAJOR || v_minor != DB_VERSION_MINOR) {
		fprintf(stderr, DB_STR_A("5118",
		    "%s: version %d.%d doesn't match library version %d.%d\n",
		    "%s %d %d %d %d\n"), progname,
		    DB_VERSION_MAJOR, DB_VERSION_MINOR,
		    v_major, v_minor);
		return EXIT_FAILURE;
	}
	return 0;
}
