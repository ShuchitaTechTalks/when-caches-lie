package dev.demo.cachelie.web;

import dev.demo.cachelie.diagnostics.ProxyChainReporter;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory valkey;
    private final ProxyChainReporter chain;

    public AdminController(JdbcTemplate jdbc, RedisConnectionFactory valkey, ProxyChainReporter chain) {
        this.jdbc = jdbc;
        this.valkey = valkey;
        this.chain = chain;
    }

    /** Plain-text view of which advisor wraps which, for one service bean (naive, safe, aware, lua, ...). */
    @GetMapping(value = "/admin/chain", produces = "text/plain")
    public String chain(@RequestParam(defaultValue = "naive") String bean) {
        return chain.report(bean);
    }

    /** Back to a known state: three products in Postgres and an empty Valkey. */
    @PostMapping("/admin/reset")
    public Map<String, String> reset() {
        jdbc.execute("TRUNCATE TABLE product");
        jdbc.update("INSERT INTO product (id, name, price, version) VALUES (1, 'Keyboard', 100, 0), (2, 'Mouse', 50, 0), (3, 'Monitor', 300, 0)");
        try (var conn = valkey.getConnection()) { conn.serverCommands().flushAll(); }
        return Map.of("postgres", "3 products, Keyboard=100", "valkey", "empty");
    }
}
