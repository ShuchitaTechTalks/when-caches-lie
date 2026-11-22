package dev.demo.cachelie.diagnostics;

import org.aopalliance.aop.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows, in plain text, which advisor wraps which. Printed at startup and served at GET /admin/chain.
 * Two views:
 *   1. every Advisor bean in the order it was REGISTERED (this is what decides a tie on the order value)
 *   2. the real proxy chain of one service bean, OUTER first (Advised.getAdvisors())
 */
@Component
public class ProxyChainReporter {
    private static final Logger log = LoggerFactory.getLogger("demo.chain");
    private final ConfigurableApplicationContext ctx;

    public ProxyChainReporter(ConfigurableApplicationContext ctx) { this.ctx = ctx; }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() { log.info("\n{}", report("naive")); }

    public String report(String serviceBean) {
        StringBuilder sb = new StringBuilder();
        sb.append("==================== ORDERING OF ADVISORS AND PROXIES ====================\n");
        sb.append("demo.aop.order = ").append(ctx.getEnvironment().getProperty("demo.aop.order", "tx-outer")).append("\n\n");

        sb.append("1) Advisor beans, in REGISTRATION order (equal order values keep this order)\n");
        sb.append(String.format("   %-3s %-62s %-12s%n", "#", "bean name", "order"));
        int i = 0;
        for (String name : ctx.getBeanFactory().getBeanDefinitionNames()) {
            if (!ctx.isTypeMatch(name, Advisor.class)) continue;
            Object bean = ctx.getBean(name);
            sb.append(String.format("   %-3d %-62s %-12s%n", ++i, shorten(name, 62), orderOf(bean)));
        }

        sb.append("\n2) Proxy chain of bean '").append(serviceBean).append("'  (first = OUTERMOST, runs first on the way in)\n");
        Object bean = ctx.getBean(serviceBean);
        if (!(bean instanceof Advised advised)) {
            sb.append("   not a Spring AOP proxy: no advisors apply\n");
            return sb.toString();
        }
        sb.append("   proxy type: ").append(advised.isProxyTargetClass() ? "CGLIB (class-based)" : "JDK (interface-based)").append("\n");
        List<String> names = new ArrayList<>();
        int pos = 0;
        for (Advisor adv : advised.getAdvisors()) {
            Advice advice = adv.getAdvice();
            String n = advice.getClass().getSimpleName();
            names.add(n);
            sb.append(String.format("   %d. %-26s order=%-12s %s%n", ++pos, n, orderOf(adv), pos == 1 ? "<- OUTERMOST" : ""));
        }
        sb.append("   ").append(++pos).append(". target method: ").append(AopUtils.getTargetClass(bean).getSimpleName()).append("\n\n");

        int tx = names.indexOf("TransactionInterceptor"), cache = names.indexOf("CacheInterceptor");
        sb.append("3) What it means\n");
        if (tx < 0 || cache < 0) sb.append("   transaction or cache advice is missing from this bean\n");
        else if (tx < cache) sb.append("   TransactionInterceptor wraps CacheInterceptor.\n"
                + "   @CachePut / @CacheEvict run INSIDE the transaction, BEFORE the commit.\n"
                + "   If the commit then fails, the cache has already changed: GHOST WRITE possible.\n");
        else sb.append("   CacheInterceptor wraps TransactionInterceptor.\n"
                + "   @CachePut / @CacheEvict run AFTER the commit; a failed commit throws past them.\n");
        sb.append("==========================================================================\n");
        return sb.toString();
    }

    private static String orderOf(Object o) {
        if (!(o instanceof Ordered ordered)) return "n/a";
        int v = ordered.getOrder();
        return v == Ordered.LOWEST_PRECEDENCE ? "LOWEST" : v == Ordered.HIGHEST_PRECEDENCE ? "HIGHEST" : String.valueOf(v);
    }

    private static String shorten(String s, int max) { return s.length() <= max ? s : "..." + s.substring(s.length() - (max - 3)); }
}
