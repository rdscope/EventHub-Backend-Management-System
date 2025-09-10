package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.model.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

//    Page<Event> findAllByOrganizer_Id(Long organizerId, Pageable pageable);

    @Query("select distinct e from Event e left join e.coOrganizers co where e.organizer.id = :userId or co.id = :userId")
    // left join e.coOrganizers co：把協同主辦的使用者連上來。
    Page<Event> findAllOwnedBy(@Param("userId") Long userId, Pageable pageable);
}