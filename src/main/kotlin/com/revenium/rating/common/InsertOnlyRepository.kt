package com.revenium.rating.common

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

/**
 * Entities use application-assigned UUIDs, so Spring Data's save() would treat a new entity as existing and
 * merge it: an extra SELECT and, worse, a silent overwrite if the id ever collided. Financial evidence is
 * inserted with persist(), which fails instead of overwriting.
 */
interface InsertOnlyRepository<T : Any> {

    fun persist(entity: T): T

    fun persistAndFlush(entity: T): T
}

class InsertOnlyRepositoryImpl<T : Any> : InsertOnlyRepository<T> {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun persist(entity: T): T {
        entityManager.persist(entity)
        return entity
    }

    override fun persistAndFlush(entity: T): T {
        entityManager.persist(entity)
        entityManager.flush()
        return entity
    }
}
