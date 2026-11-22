package dev.demo.cachelie.service.versioned;

import dev.demo.cachelie.domain.Product;
import dev.demo.cachelie.domain.ProductRepository;
import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import dev.demo.cachelie.service.support.Tx;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** STEP 4: every cache write carries the row version, and Valkey applies it atomically via Lua. */
@Service("lua")
public class LuaProductService implements ProductOps {
    private static final Logger log = LoggerFactory.getLogger("demo.lua");
    private static final RedisScript<String> CAS = RedisScript.of(new ClassPathResource("cas.lua"), String.class);

    private final ProductRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public LuaProductService(ProductRepository repo, StringRedisTemplate redis, ObjectMapper json) {
        this.repo = repo;
        this.redis = redis;
        this.json = json;
    }

    private static String key(long id) { return "lua:product:" + id; }

    private String casPut(ProductView v) {
        try {
            return redis.execute(CAS, List.of(key(v.id())), json.writeValueAsString(v), String.valueOf(v.version()), "600");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ProductView read(long id, long delayMs) {
        Object cached = redis.opsForHash().get(key(id), "payload");
        if (cached != null) {
            log.info("cache HIT for {}", id);
            return parse(cached.toString());
        }
        log.info("cache MISS for {} -> reading DB", id);
        ProductView view = ProductView.of(repo.findById(id).orElseThrow());
        ProductOps.pause(delayMs);
        log.info("reader CAS put {} version {} -> {}", id, view.version(), casPut(view));
        return view;
    }

    @Override
    @Transactional
    public ProductView update(long id, String name, int price) {
        Product p = repo.save(applied(repo.findById(id).orElseThrow(), name, price));
        repo.flush();                                       // bumps @Version so we know the new version
        ProductView view = ProductView.of(p);
        Tx.afterCommit(() -> log.info("afterCommit: writer CAS put {} version {} -> {}", id, view.version(), casPut(view)));
        return view;
    }

    private static Product applied(Product p, String name, int price) { p.setName(name); p.setPrice(price); return p; }

    private ProductView parse(String s) {
        try { return json.readValue(s, ProductView.class); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
