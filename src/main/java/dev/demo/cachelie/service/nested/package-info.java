/**
 * Demos 5 and 6: the transaction is opened OUTSIDE the method that carries the cache annotation.
 *   nested      = CheckoutService      (@Transactional) -> NaiveProductService (@Transactional + @CachePut)  breaks
 *   nestedaware = CheckoutAwareService (@Transactional) -> AwareProductService (transactionAware() cache)    fixed
 * The inner @Transactional only joins, so the commit happens in the checkout, after naive's cache write.
 * With demo.aop.order=cache-outer, nested still leaves Valkey out of sync; nestedaware stays in sync.
 */
package dev.demo.cachelie.service.nested;
