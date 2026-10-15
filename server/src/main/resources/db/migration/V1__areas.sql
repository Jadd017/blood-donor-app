-- Areas a donor can live in. For now: Beirut plus every Lebanese district (qada).
-- Towns can be added later as extra rows inside a district.
-- Requires PostGIS (created by a superuser, never by migrations).

CREATE TABLE areas (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL,
    district    text NOT NULL,
    governorate text NOT NULL,
    location    geography(Point, 4326) NOT NULL,
    CONSTRAINT areas_name_district_key UNIQUE (name, district)
);

-- NOTE: coordinates are APPROXIMATE district centroids (or the main town) and
-- still need a human review. ST_MakePoint takes (longitude, latitude).
INSERT INTO areas (name, district, governorate, location)
SELECT name, district, governorate, ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography
FROM (VALUES
    -- Beirut
    ('Beirut',           'Beirut',           'Beirut',         33.8938, 35.5018),
    -- Mount Lebanon
    ('Baabda',           'Baabda',           'Mount Lebanon',  33.8340, 35.5440),
    ('Aley',             'Aley',             'Mount Lebanon',  33.8060, 35.6000),
    ('Chouf',            'Chouf',            'Mount Lebanon',  33.6950, 35.5800),
    ('Matn',             'Matn',             'Mount Lebanon',  33.9000, 35.6600),
    -- Keserwan-Jbeil
    ('Keserwan',         'Keserwan',         'Keserwan-Jbeil', 34.0200, 35.7000),
    ('Jbeil',            'Jbeil',            'Keserwan-Jbeil', 34.1300, 35.7500),
    -- North
    ('Tripoli',          'Tripoli',          'North',          34.4360, 35.8360),
    ('Zgharta',          'Zgharta',          'North',          34.3600, 35.9200),
    ('Koura',            'Koura',            'North',          34.3000, 35.8000),
    ('Batroun',          'Batroun',          'North',          34.2200, 35.7700),
    ('Bsharri',          'Bsharri',          'North',          34.2500, 36.0200),
    ('Miniyeh-Danniyeh', 'Miniyeh-Danniyeh', 'North',          34.4200, 36.0000),
    -- Akkar
    ('Akkar',            'Akkar',            'Akkar',          34.5300, 36.1800),
    -- Bekaa
    ('Zahle',            'Zahle',            'Bekaa',          33.8500, 35.9000),
    ('West Bekaa',       'West Bekaa',       'Bekaa',          33.6000, 35.7500),
    ('Rashaya',          'Rashaya',          'Bekaa',          33.5000, 35.8400),
    -- Baalbek-Hermel
    ('Baalbek',          'Baalbek',          'Baalbek-Hermel', 34.0000, 36.2000),
    ('Hermel',           'Hermel',           'Baalbek-Hermel', 34.3900, 36.3800),
    -- South
    ('Sidon',            'Sidon',            'South',          33.5600, 35.4000),
    ('Tyre',             'Tyre',             'South',          33.2500, 35.2500),
    ('Jezzine',          'Jezzine',          'South',          33.5400, 35.5800),
    -- Nabatieh
    ('Nabatieh',         'Nabatieh',         'Nabatieh',       33.3800, 35.4800),
    ('Marjayoun',        'Marjayoun',        'Nabatieh',       33.3600, 35.5900),
    ('Hasbaya',          'Hasbaya',          'Nabatieh',       33.4000, 35.6800),
    ('Bint Jbeil',       'Bint Jbeil',       'Nabatieh',       33.1200, 35.4300)
) AS seed(name, district, governorate, lat, lon);
