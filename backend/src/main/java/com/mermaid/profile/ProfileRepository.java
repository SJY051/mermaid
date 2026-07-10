package com.mermaid.profile;

import com.mermaid.profile.domain.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately no {@code @EntityGraph} fetching both collections.
 *
 * <p>Hibernate cannot fetch-join two {@code List} associations at once — it throws {@code
 * MultipleBagFetchException} at query time, and every profile request 500s. A profile has a handful
 * of allergies and favorites, so the two extra lazy selects inside the transaction cost nothing.
 * Reach for {@code @BatchSize} or a {@code Set} only if that ever stops being true.
 */
public interface ProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByDeviceId(String deviceId);
}
