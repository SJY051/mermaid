package com.mermaid.profile;

import com.mermaid.profile.domain.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<UserProfile, Long> {

    @EntityGraph(attributePaths = {"allergies", "favorites"})
    Optional<UserProfile> findByDeviceId(String deviceId);
}
