#include "db_config.h"

#include "db_int.h"
#include "dbinc/db_page.h"
#include "dbinc/btree.h"
#include "dbinc/mp.h"
#include "dbinc/qam.h"

static int	 __db_hmeta __P((ENV *, DB *, HMETA *, u_int32_t));
#ifdef HAVE_STATISTICS
static int	 __db_prtree_custom __P((DB *, DB_TXN *,
		    u_int32_t, db_pgno_t, db_pgno_t));
#endif

/*
 *	Dump the tree to a file.
 */
int
__db_dumptree_custom(DB *dbp, DB_TXN *txn, char *op, char *name, db_pgno_t first, db_pgno_t last)
{
	ENV *env;
	FILE *fp, *orig_fp;
	u_int32_t flags;
	int ret;

	env = dbp->env;

	for (flags = 0; *op != '\0'; ++op)
		switch (*op) {
		case 'a':
			LF_SET(DB_PR_PAGE);
			break;
		default:
			return (EINVAL);
		}

	if (name != NULL) {
		if ((fp = fopen(name, "w")) == NULL)
			return (__os_get_errno());

		orig_fp = dbp->dbenv->db_msgfile;
		dbp->dbenv->db_msgfile = fp;
	} else
		fp = orig_fp = NULL;

	ret = __db_prtree_custom(dbp, txn, flags, first, last);

	if (fp != NULL) {
		(void)fclose(fp);
		env->dbenv->db_msgfile = orig_fp;
	}

	return (ret);
}

static const FN __db_flags_fn[] = {
	{ DB_AM_CHKSUM,			"checksumming" },
	{ DB_AM_COMPENSATE,		"created by compensating transaction" },
	{ DB_AM_CREATED,		"database created" },
	{ DB_AM_CREATED_MSTR,		"encompassing file created" },
	{ DB_AM_DBM_ERROR,		"dbm/ndbm error" },
	{ DB_AM_DELIMITER,		"variable length" },
	{ DB_AM_DISCARD,		"discard cached pages" },
	{ DB_AM_DUP,			"duplicates" },
	{ DB_AM_DUPSORT,		"sorted duplicates" },
	{ DB_AM_ENCRYPT,		"encrypted" },
	{ DB_AM_FIXEDLEN,		"fixed-length records" },
	{ DB_AM_INMEM,			"in-memory" },
	{ DB_AM_IN_RENAME,		"file is being renamed" },
	{ DB_AM_NOT_DURABLE,		"changes not logged" },
	{ DB_AM_OPEN_CALLED,		"open called" },
	{ DB_AM_PAD,			"pad value" },
	{ DB_AM_PGDEF,			"default page size" },
	{ DB_AM_RDONLY,			"read-only" },
	{ DB_AM_READ_UNCOMMITTED,	"read-uncommitted" },
	{ DB_AM_RECNUM,			"Btree record numbers" },
	{ DB_AM_RECOVER,		"opened for recovery" },
	{ DB_AM_RENUMBER,		"renumber" },
	{ DB_AM_REVSPLITOFF,		"no reverse splits" },
	{ DB_AM_SECONDARY,		"secondary" },
	{ DB_AM_SNAPSHOT,		"load on open" },
	{ DB_AM_SUBDB,			"subdatabases" },
	{ DB_AM_SWAP,			"needswap" },
	{ DB_AM_TXN,			"transactional" },
	{ DB_AM_VERIFYING,		"verifier" },
	{ 0,				NULL }
};

/*
 *	Print out the entire tree.
 */
static int
__db_prtree_custom(dbp, txn, flags, first, last)
	DB *dbp;
	DB_TXN *txn;
	u_int32_t flags;
	db_pgno_t first, last;
{
	DB_MPOOLFILE *mpf;
	PAGE *h;
	db_pgno_t i;
	int ret;

	mpf = dbp->mpf;

	if (dbp->type == DB_QUEUE)
		return (__db_prqueue(dbp, flags));

	/*
	 * Find out the page number of the last page in the database, then
	 * dump each page.
	 */
	if (last == PGNO_INVALID &&
	    (ret = __memp_get_last_pgno(mpf, &last)) != 0)
		return (ret);
	for (i = first; i <= last; ++i) {
		if ((ret = __memp_fget(mpf, &i, NULL, txn, 0, &h)) != 0)
			return (ret);
		(void)__db_prpage(dbp, h, flags);
		if ((ret = __memp_fput(mpf, NULL, h, dbp->priority)) != 0)
			return (ret);
	}

	return (0);
}

/*
 * __db_prpage
 *	-- Print out a page.
 *
 * PUBLIC: int __db_prpage __P((DB *, PAGE *, u_int32_t));
 */
int
__db_prpage(dbp, h, flags)
	DB *dbp;
	PAGE *h;
	u_int32_t flags;
{
	DB_MSGBUF mb;
	u_int32_t pagesize;
	/*
	 * !!!
	 * Find out the page size.  We don't want to do it the "right" way,
	 * by reading the value from the meta-data page, that's going to be
	 * slow.  Reach down into the mpool region.
	 */
	pagesize = (u_int32_t)dbp->mpf->mfp->pagesize;
	DB_MSGBUF_INIT(&mb);
	return (__db_prpage_int(dbp->env,
	    &mb, dbp, "", h, pagesize, NULL, flags));
}

/*
 *	Print out common metadata information.
 */
static void
__db_meta(env, dbp, dbmeta, fn, flags)
	DB *dbp;
	ENV *env;
	DBMETA *dbmeta;
	FN const *fn;
	u_int32_t flags;
{
	DB_MPOOLFILE *mpf;
	DB_MSGBUF mb;
	PAGE *h;
	db_pgno_t pgno;
	u_int8_t *p;
	int cnt, ret;
	const char *sep;

	DB_MSGBUF_INIT(&mb);

	__db_msg(env, "\tmagic: %#lx", (u_long)dbmeta->magic);
	__db_msg(env, "\tversion: %lu", (u_long)dbmeta->version);
	__db_msg(env, "\tpagesize: %lu", (u_long)dbmeta->pagesize);
	__db_msg(env, "\ttype: %lu", (u_long)dbmeta->type);
	__db_msg(env, "\tmetaflags %#lx", (u_long)dbmeta->metaflags);
	__db_msg(env, "\tkeys: %lu\trecords: %lu",
	    (u_long)dbmeta->key_count, (u_long)dbmeta->record_count);
	if (dbmeta->nparts)
		__db_msg(env, "\tnparts: %lu", (u_long)dbmeta->nparts);

	/*
	 * If we're doing recovery testing, don't display the free list,
	 * it may have changed and that makes the dump diff not work.
	 */
	if (dbp != NULL && !LF_ISSET(DB_PR_RECOVERYTEST)) {
		mpf = dbp->mpf;
		__db_msgadd(
		    env, &mb, "\tfree list: %lu", (u_long)dbmeta->free);
		for (pgno = dbmeta->free,
		    cnt = 0, sep = ", "; pgno != PGNO_INVALID;) {
			if ((ret = __memp_fget(mpf,
			     &pgno, NULL, NULL, 0, &h)) != 0) {
				DB_MSGBUF_FLUSH(env, &mb);
				__db_msg(env,
			    "Unable to retrieve free-list page: %lu: %s",
				    (u_long)pgno, db_strerror(ret));
				break;
			}
			pgno = h->next_pgno;
			(void)__memp_fput(mpf, NULL, h, dbp->priority);
			__db_msgadd(env, &mb, "%s%lu", sep, (u_long)pgno);
			if (++cnt % 10 == 0) {
				DB_MSGBUF_FLUSH(env, &mb);
				cnt = 0;
				sep = "\t";
			} else
				sep = ", ";
		}
		DB_MSGBUF_FLUSH(env, &mb);
		__db_msg(env, "\tlast_pgno: %lu", (u_long)dbmeta->last_pgno);
	}

	if (fn != NULL) {
		DB_MSGBUF_FLUSH(env, &mb);
		__db_msgadd(env, &mb, "\tflags: %#lx", (u_long)dbmeta->flags);
		__db_prflags(env, &mb, dbmeta->flags, fn, " (", ")");
	}

	DB_MSGBUF_FLUSH(env, &mb);
	__db_msgadd(env, &mb, "\tuid: ");
	for (p = (u_int8_t *)dbmeta->uid,
	    cnt = 0; cnt < DB_FILE_ID_LEN; ++cnt) {
		__db_msgadd(env, &mb, "%x", *p++);
		if (cnt < DB_FILE_ID_LEN - 1)
			__db_msgadd(env, &mb, " ");
	}
	DB_MSGBUF_FLUSH(env, &mb);
}

/*
 * __db_hmeta --
 *	Print out the hash meta-data page.
 */
static int
__db_hmeta(env, dbp, h, flags)
	ENV *env;
	DB *dbp;
	HMETA *h;
	u_int32_t flags;
{
	static const FN fn[] = {
		{ DB_HASH_DUP,		"duplicates" },
		{ DB_HASH_SUBDB,	"multiple-databases" },
		{ DB_HASH_DUPSORT,	"sorted duplicates" },
		{ 0,			NULL }
	};
	DB_MSGBUF mb;
	int i;

	DB_MSGBUF_INIT(&mb);

	__db_meta(env, dbp, (DBMETA *)h, fn, flags);

	__db_msg(env, "\tmax_bucket: %lu", (u_long)h->max_bucket);
	__db_msg(env, "\thigh_mask: %#lx", (u_long)h->high_mask);
	__db_msg(env, "\tlow_mask:  %#lx", (u_long)h->low_mask);
	__db_msg(env, "\tffactor: %lu", (u_long)h->ffactor);
	__db_msg(env, "\tnelem: %lu", (u_long)h->nelem);
	__db_msg(env, "\th_charkey: %#lx", (u_long)h->h_charkey);
	__db_msgadd(env, &mb, "\tspare points:\n\t");
	for (i = 0; i < NCACHED; i++) {
		__db_msgadd(env, &mb, "%lu (%lu) ", (u_long)h->spares[i],
		    (u_long)(h->spares[i] == 0 ?
		    0 : h->spares[i] + (i == 0 ? 0 : 1 << (i-1))));
		if ((i + 1) % 8 == 0)
			__db_msgadd(env, &mb, "\n\t");
	}
	DB_MSGBUF_FLUSH(env, &mb);

	return (0);
}

/*
 * For printing pages from the log we may be passed the data segment
 * separate from the header, if so then it starts at HOFFSET.
 */
#define	PR_ENTRY(dbp, h, i, data)				\
	(data == NULL ? P_ENTRY(dbp, h, i) :			\
	    (u_int8_t *)data + P_INP(dbp, h)[i] - HOFFSET(h))
/*
 * __db_prpage_int
 *	-- Print out a page.
 *
 * PUBLIC: int __db_prpage_int __P((ENV *, DB_MSGBUF *,
 * PUBLIC:      DB *, char *, PAGE *, u_int32_t, u_int8_t *, u_int32_t));
 */
int
__db_prpage_int(env, mbp, dbp, lead, h, pagesize, data, flags)
	ENV *env;
	DB_MSGBUF *mbp;
	DB *dbp;
	char *lead;
	PAGE *h;
	u_int32_t pagesize;
	u_int8_t *data;
	u_int32_t flags;
{
	BINTERNAL *bi;
	BKEYDATA *bk;
	HOFFPAGE a_hkd;
	QAMDATA *qp, *qep;
	RINTERNAL *ri;
	HEAPHDR *hh;
	HEAPSPLITHDR *hs;
	db_indx_t dlen, len, i, *inp, max;
	db_pgno_t pgno;
	db_recno_t recno;
	u_int32_t qlen;
	u_int8_t *ep, *hk, *p;
	int deleted, ret;
	const char *s;
	void *hdata, *sp;

	/*
	 * If we're doing recovery testing and this page is P_INVALID,
	 * assume it's a page that's on the free list, and don't display it.
	 */
	if (LF_ISSET(DB_PR_RECOVERYTEST) && TYPE(h) == P_INVALID)
		return (0);

	if ((s = __db_pagetype_to_string(TYPE(h))) == NULL) {
		__db_msg(env, "%sILLEGAL PAGE TYPE: page: %lu type: %lu",
		    lead, (u_long)h->pgno, (u_long)TYPE(h));
		return (EINVAL);
	}

	/* Page number, page type. */
	__db_msgadd(env, mbp, "%spage %lu: %s:", lead, (u_long)h->pgno, s);

	/*
	 * LSNs on a metadata page will be different from the original after an
	 * abort, in some cases.  Don't display them if we're testing recovery.
	 */
	if (!LF_ISSET(DB_PR_RECOVERYTEST) ||
	    (TYPE(h) != P_BTREEMETA && TYPE(h) != P_HASHMETA &&
	    TYPE(h) != P_QAMMETA && TYPE(h) != P_QAMDATA &&
	    TYPE(h) != P_HEAPMETA))
		__db_msgadd(env, mbp, " LSN [%lu][%lu]:",
		    (u_long)LSN(h).file, (u_long)LSN(h).offset);

	/*
	 * Page level (only applicable for Btree/Recno, but we always display
	 * it, for no particular reason, except for Heap.
	 */
	if (!HEAPTYPE(h))
	    __db_msgadd(env, mbp, " level %lu", (u_long)h->level);

	/* Record count. */
	if (TYPE(h) == P_IBTREE || TYPE(h) == P_IRECNO ||
	    (dbp != NULL && TYPE(h) == P_LRECNO &&
	    h->pgno == ((BTREE *)dbp->bt_internal)->bt_root))
		__db_msgadd(env, mbp, " records: %lu", (u_long)RE_NREC(h));
	DB_MSGBUF_FLUSH(env, mbp);

	switch (TYPE(h)) {
	case P_HASHMETA:
		return (__db_hmeta(env, dbp, (HMETA *)h, flags));
	case P_QAMDATA:				/* Should be meta->start. */
		if (!LF_ISSET(DB_PR_PAGE) || dbp == NULL)
			return (0);

		qlen = ((QUEUE *)dbp->q_internal)->re_len;
		recno = (h->pgno - 1) * QAM_RECNO_PER_PAGE(dbp) + 1;
		i = 0;
		qep = (QAMDATA *)((u_int8_t *)h + pagesize - qlen);
		for (qp = QAM_GET_RECORD(dbp, h, i); qp < qep;
		    recno++, i++, qp = QAM_GET_RECORD(dbp, h, i)) {
			if (!F_ISSET(qp, QAM_SET))
				continue;

			__db_msgadd(env, mbp, "%s",
			    F_ISSET(qp, QAM_VALID) ? "\t" : "       D");
			__db_msgadd(env, mbp, "[%03lu] %4lu ", (u_long)recno,
			    (u_long)((u_int8_t *)qp - (u_int8_t *)h));
			__db_prbytes(env, mbp, qp->data, qlen);
		}
		return (0);
	default:
		break;
	}

	s = "\t";
	if (!HEAPTYPE(h) && TYPE(h) != P_IBTREE && TYPE(h) != P_IRECNO) {
		__db_msgadd(env, mbp, "%sprev: %4lu next: %4lu",
		    s, (u_long)PREV_PGNO(h), (u_long)NEXT_PGNO(h));
		s = " ";
	}

	if (HEAPTYPE(h)) {
		__db_msgadd(env, mbp, "%shigh indx: %4lu free indx: %4lu", s,
		    (u_long)HEAP_HIGHINDX(h), (u_long)HEAP_FREEINDX(h));
		s = " ";
	}

	if (TYPE(h) == P_OVERFLOW) {
		__db_msgadd(env, mbp,
		    "%sref cnt: %4lu ", s, (u_long)OV_REF(h));
		if (dbp == NULL)
			__db_msgadd(env, mbp,
			    " len: %4lu ", (u_long)OV_LEN(h));
		else
			__db_prbytes(env,
			    mbp, (u_int8_t *)h + P_OVERHEAD(dbp), OV_LEN(h));
		return (0);
	}
	__db_msgadd(env, mbp, "%sentries: %4lu", s, (u_long)NUM_ENT(h));
	__db_msgadd(env, mbp, " offset: %4lu", (u_long)HOFFSET(h));
	DB_MSGBUF_FLUSH(env, mbp);

	if (dbp == NULL || TYPE(h) == P_INVALID || !LF_ISSET(DB_PR_PAGE))
		return (0);

	if (data != NULL)
		pagesize += HOFFSET(h);
	else if (pagesize < HOFFSET(h))
		return (0);

	ret = 0;
	inp = P_INP(dbp, h);
	max = TYPE(h) == P_HEAP ? HEAP_HIGHINDX(h) + 1 : NUM_ENT(h);
	for (i = 0; i < max; i++) {
		if (TYPE(h) == P_HEAP && inp[i] == 0)
			continue;
		if ((uintptr_t)(P_ENTRY(dbp, h, i) - (u_int8_t *)h) <
		    (uintptr_t)(P_OVERHEAD(dbp)) ||
		    (size_t)(P_ENTRY(dbp, h, i) - (u_int8_t *)h) >= pagesize) {
			__db_msg(env,
			    "ILLEGAL PAGE OFFSET: indx: %lu of %lu",
			    (u_long)i, (u_long)inp[i]);
			ret = EINVAL;
			continue;
		}
		deleted = 0;
		switch (TYPE(h)) {
		case P_HASH_UNSORTED:
		case P_HASH:
		case P_IBTREE:
		case P_IRECNO:
			sp = PR_ENTRY(dbp, h, i, data);
			break;
		case P_HEAP:
			sp = P_ENTRY(dbp, h, i);
			break;
		case P_LBTREE:
			sp = PR_ENTRY(dbp, h, i, data);
			deleted = i % 2 == 0 &&
			    B_DISSET(GET_BKEYDATA(dbp, h, i + O_INDX)->type);
			break;
		case P_LDUP:
		case P_LRECNO:
			sp = PR_ENTRY(dbp, h, i, data);
			deleted = B_DISSET(GET_BKEYDATA(dbp, h, i)->type);
			break;
		default:
			goto type_err;
		}
		__db_msgadd(env, mbp, "%s", deleted ? "       D" : "\t");
		__db_msgadd(
		    env, mbp, "[%03lu] %4lu ", (u_long)i, (u_long)inp[i]);
		switch (TYPE(h)) {
		case P_HASH_UNSORTED:
		case P_HASH:
			hk = sp;
			switch (HPAGE_PTYPE(hk)) {
			case H_OFFDUP:
				memcpy(&pgno,
				    HOFFDUP_PGNO(hk), sizeof(db_pgno_t));
				__db_msgadd(env, mbp,
				    "%4lu [offpage dups]", (u_long)pgno);
				DB_MSGBUF_FLUSH(env, mbp);
				break;
			case H_DUPLICATE:
				/*
				 * If this is the first item on a page, then
				 * we cannot figure out how long it is, so
				 * we only print the first one in the duplicate
				 * set.
				 */
				if (i != 0)
					len = LEN_HKEYDATA(dbp, h, 0, i);
				else
					len = 1;

				__db_msgadd(env, mbp, "Duplicates:");
				DB_MSGBUF_FLUSH(env, mbp);
				for (p = HKEYDATA_DATA(hk),
				    ep = p + len; p < ep;) {
					memcpy(&dlen, p, sizeof(db_indx_t));
					p += sizeof(db_indx_t);
					__db_msgadd(env, mbp, "\t\t");
					__db_prbytes(env, mbp, p, dlen);
					p += sizeof(db_indx_t) + dlen;
				}
				break;
			case H_KEYDATA:
				__db_prbytes(env, mbp, HKEYDATA_DATA(hk),
				    LEN_HKEYDATA(dbp, h, i == 0 ?
				    pagesize : 0, i));
				break;
			case H_OFFPAGE:
				memcpy(&a_hkd, hk, HOFFPAGE_SIZE);
				__db_msgadd(env, mbp,
				    "overflow: total len: %4lu page: %4lu",
				    (u_long)a_hkd.tlen, (u_long)a_hkd.pgno);
				DB_MSGBUF_FLUSH(env, mbp);
				break;
			default:
				DB_MSGBUF_FLUSH(env, mbp);
				__db_msg(env, "ILLEGAL HASH PAGE TYPE: %lu",
				    (u_long)HPAGE_PTYPE(hk));
				ret = EINVAL;
				break;
			}
			break;
		case P_IBTREE:
			bi = sp;

			if (F_ISSET(dbp, DB_AM_RECNUM))
				__db_msgadd(env, mbp,
				    "count: %4lu ", (u_long)bi->nrecs);
			__db_msgadd(env, mbp,
			    "pgno: %4lu type: %lu ",
			    (u_long)bi->pgno, (u_long)bi->type);
			switch (B_TYPE(bi->type)) {
			case B_KEYDATA:
				__db_prbytes(env, mbp, bi->data, bi->len);
				break;
			case B_DUPLICATE:
			default:
				DB_MSGBUF_FLUSH(env, mbp);
				__db_msg(env, "ILLEGAL BINTERNAL TYPE: %lu",
				    (u_long)B_TYPE(bi->type));
				ret = EINVAL;
				break;
			}
			break;
		case P_IRECNO:
			ri = sp;
			__db_msgadd(env, mbp, "entries %4lu pgno %4lu",
			    (u_long)ri->nrecs, (u_long)ri->pgno);
			DB_MSGBUF_FLUSH(env, mbp);
			break;
		case P_LBTREE:
		case P_LDUP:
		case P_LRECNO:
			bk = sp;
			switch (B_TYPE(bk->type)) {
			case B_KEYDATA:
				__db_prbytes(env, mbp, bk->data, bk->len);
				break;
			case B_DUPLICATE:
			default:
				DB_MSGBUF_FLUSH(env, mbp);
				__db_msg(env,
			    "ILLEGAL DUPLICATE/LBTREE/LRECNO TYPE: %lu",
				    (u_long)B_TYPE(bk->type));
				ret = EINVAL;
				break;
			}
			break;
		case P_HEAP:
			hh = sp;
			if (!F_ISSET(hh,HEAP_RECSPLIT))
				hdata = (u_int8_t *)hh + sizeof(HEAPHDR);
			else {
				hs = sp;
				__db_msgadd(env, mbp,
				     "split: 0x%02x tsize: %lu next: %lu.%lu ",
				     hh->flags, (u_long)hs->tsize,
				     (u_long)hs->nextpg, (u_long)hs->nextindx);

				hdata = (u_int8_t *)hh + sizeof(HEAPSPLITHDR);
			}
			__db_prbytes(env, mbp, hdata, hh->size);
			break;
		default:
type_err:		DB_MSGBUF_FLUSH(env, mbp);
			__db_msg(env,
			    "ILLEGAL PAGE TYPE: %lu", (u_long)TYPE(h));
			ret = EINVAL;
			continue;
		}
	}
	return (ret);
}

# define isprint_custom(c) isprint((c))

/*
 * Print out a data element.
 * This is the core function from db-5.3 library that is being changed to print
 * binary streams on a non-ambiguous way.
 */
void
__db_prbytes(ENV *env, DB_MSGBUF *mbp, u_int8_t *bytes, u_int32_t len) {
	u_int8_t *p;
	u_int32_t i, not_printable;
	int msg_truncated;

	__db_msgadd(env, mbp, "len: %3lu", (u_long)len);
	if (len != 0) {
		__db_msgadd(env, mbp, " data: ");

		/*
		 * Print the first N bytes of the data. If all chunk bytes are printable characters, print
		 * it as text, else print it in hex. Original heuristic lead to ambiguous generation of
		 * binary data. New change allows external programs to read binary data as is.
		 */
		if ( len > env->data_len ) {
			len = env->data_len;
			msg_truncated = 1;
		} else {
            msg_truncated = 0;
        }
		not_printable = 0;

		for ( p = bytes, i = 0; i < len; ++i, ++p ) {
			if (!isprint((int)*p) && *p != '\t' && *p != '\n') {
				if (i == len - 1 && *p == '\0') {
                    break;
                }
				if (++not_printable > 0) {
                    break;
                }
			}
		}

		if ( not_printable == 0 ) {
            for (p = bytes, i = len; i > 0; --i, ++p) {
                if (isprint_custom((int) *p)) {
                    __db_msgadd(env, mbp, "%c", *p);
                } else {
                    __db_msgadd(env, mbp, "\\%x", (u_int) *p);
                }
            }
        } else {
            for (p = bytes, i = len; i > 0; --i, ++p) {
                __db_msgadd(env, mbp, "%.2x", (u_int) *p);
            }
        }
		if (msg_truncated) {
            __db_msgadd(env, mbp, "...");
        }
	}
	DB_MSGBUF_FLUSH(env, mbp);
}

/*
 *	Print out flags values.
 */
void
__db_prflags(env, mbp, flags, fn, prefix, suffix)
	ENV *env;
	DB_MSGBUF *mbp;
	u_int32_t flags;
	FN const *fn;
	const char *prefix, *suffix;
{
	DB_MSGBUF mb;
	const FN *fnp;
	int found, standalone;
	const char *sep;

	if (fn == NULL) {
        return;
    }

	/*
	 * If it's a standalone message, output the suffix (which will be the
	 * label), regardless of whether we found anything or not, and flush
	 * the line.
	 */
	if (mbp == NULL) {
		standalone = 1;
		mbp = &mb;
		DB_MSGBUF_INIT(mbp);
	} else {
        standalone = 0;
    }

	sep = prefix == NULL ? "" : prefix;
	for (found = 0, fnp = fn; fnp->mask != 0; ++fnp)
		if (LF_ISSET(fnp->mask)) {
			__db_msgadd(env, mbp, "%s%s", sep, fnp->name);
			sep = ", ";
			found = 1;
		}

	if ((standalone || found) && suffix != NULL)
		__db_msgadd(env, mbp, "%s", suffix);
	if (standalone)
		DB_MSGBUF_FLUSH(env, mbp);
}

/*
 * Return the name of the specified page type.
 */
const char *
__db_pagetype_to_string(type)
	u_int32_t type;
{
	char *s;

	s = NULL;
	switch (type) {
	case P_BTREEMETA:
		s = "btree metadata";
		break;
	case P_LDUP:
		s = "duplicate";
		break;
	case P_HASH_UNSORTED:
		s = "hash unsorted";
		break;
	case P_HASH:
		s = "hash";
		break;
	case P_HASHMETA:
		s = "hash metadata";
		break;
	case P_IBTREE:
		s = "btree internal";
		break;
	case P_INVALID:
		s = "invalid";
		break;
	case P_IRECNO:
		s = "recno internal";
		break;
	case P_LBTREE:
		s = "btree leaf";
		break;
	case P_LRECNO:
		s = "recno leaf";
		break;
	case P_OVERFLOW:
		s = "overflow";
		break;
	case P_QAMMETA:
		s = "queue metadata";
		break;
	case P_QAMDATA:
		s = "queue";
		break;
	case P_HEAPMETA:
		s = "heap metadata";
		break;
	case P_HEAP:
		s = "heap data";
		break;
	case P_IHEAP:
		s = "heap internal";
		break;
	default:
		/* Just return a NULL. */
		break;
	}
	return (s);
}
