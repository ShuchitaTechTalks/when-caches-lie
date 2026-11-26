package dev.demo.cachelie.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Spring Data's default is @Transactional(readOnly = true), which opens and commits a transaction of its own for
     * every read. SUPPORTS joins a running transaction (an update) but doesn't start one for a plain read, so the
     * demo log only shows the transactions that matter: the writes.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    Optional<Product> findById(Long id);
}
