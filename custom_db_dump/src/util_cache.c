#include "db_config.h"

#include "db_int.h"

/*
 *	Compute if we have enough cache.
 */
int
__db_util_cache(dbp, cachep, resizep)
	DB *dbp;
	u_int32_t *cachep;
	int *resizep;
{
	u_int32_t pgsize;
	int ret;

	/* Get the current page size. */
	if ((ret = dbp->get_pagesize(dbp, &pgsize)) != 0)
		return (ret);

    *resizep = 0;

	return (0);
}
