package dev.demo.cachelie.web;

import dev.demo.cachelie.domain.ProductView;
import dev.demo.cachelie.service.ProductOps;
import java.util.Map;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProductController {
    private final Map<String, ProductOps> ops;

    public ProductController(Map<String, ProductOps> ops) { this.ops = ops; }

    private ProductOps pick(String mode) {
        ProductOps o = ops.get(mode);
        if (o == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "mode must be one of " + ops.keySet());
        return o;
    }

    @GetMapping("/{mode}/products/{id}")
    public ProductView read(@PathVariable String mode, @PathVariable long id,
                            @RequestParam(defaultValue = "0") long delayMs) {
        return pick(mode).read(id, delayMs);
    }

    @PutMapping("/{mode}/products/{id}")
    public ProductView update(@PathVariable String mode, @PathVariable long id,
                              @RequestParam String name, @RequestParam int price) {
        return pick(mode).update(id, name, price);
    }

    /** The commit failed, so the database rolled back. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, String>> rolledBack(NestedRuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("result", "DB ROLLED BACK", "reason", String.valueOf(e.getMostSpecificCause().getMessage())));
    }
}
