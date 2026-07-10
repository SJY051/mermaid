package com.mermaid.profile.domain;

import com.mermaid.facility.domain.FacilityType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A saved pharmacy or hospital. {@code facilityId} is an {@code hpid} or a {@code ykiho}. */
@Entity
@Table(name = "favorite_facility")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FavoriteFacility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;

    @Column(name = "facility_id", nullable = false, length = 100)
    private String facilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "facility_type", nullable = false, length = 20)
    private FacilityType facilityType;

    @Column(name = "memo", length = 500)
    private String memo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FavoriteFacility(String facilityId, FacilityType facilityType, String memo) {
        this.facilityId = facilityId;
        this.facilityType = facilityType;
        this.memo = memo;
    }

    public void changeMemo(String memo) {
        this.memo = memo;
    }

    void assignTo(UserProfile profile) {
        this.profile = profile;
    }
}
