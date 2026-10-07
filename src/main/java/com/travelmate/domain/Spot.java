package com.travelmate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "spot", indexes = @Index(name = "idx_spot_city", columnList = "cityKey"))
public class Spot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable public map identity; never a device position or user identifier. */
    @Column(unique = true, length = 64)
    private String externalId;

    @Column(nullable = false, length = 32)
    private String cityKey;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 32)
    private String category;

    @Column(length = 512)
    private String intro;

    @Column(length = 256)
    private String highlight;

    @Column(length = 64)
    private String openTime;

    @Column(length = 64)
    private String recommendedDuration;

    /** 逗号分隔标签。 */
    @Column(length = 256)
    private String tags;

    @Column(length = 64)
    private String sourceName;

    @Column(length = 32)
    private String sourceStatus;

    /** OpenStreetMap / Wikipedia / web-search facts, each prefixed with its source; JSON array of strings. */
    @Column(length = 4000)
    private String extraFacts;

    @Column(length = 512)
    private String sourceUrl;

    private Double latitude;

    private Double longitude;
}
