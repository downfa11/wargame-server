package com.ns.wargame.Repository;

import com.ns.wargame.Domain.User;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserR2dbcRepository extends ReactiveCrudRepository<User, Long> {
    Flux<User> findByName(String name);
    Flux<User> findByEmail(String email);
    Mono<User> findByEmailAndPassword(String email,String password);
    Flux<User> findByNameOrderByIdDesc(String name);

    @Modifying
    @Query("DELETE FROM users WHERE name = :name")
    Mono<Void> deleteByName(String name);

}