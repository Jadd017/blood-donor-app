-- Area points more than 5 km from the OSM district centroid are moved to it.
-- Centroids are area-weighted, computed from the OSM admin_level=5 district
-- boundaries (Beirut: the governorate). Data (c) OpenStreetMap contributors, ODbL.
-- ST_MakePoint takes (longitude, latitude).

UPDATE areas a
SET location = ST_SetSRID(ST_MakePoint(fix.lon, fix.lat), 4326)::geography
FROM (VALUES
    ('Baabda',           33.8437, 35.6722),  -- was 11.9 km off
    ('Keserwan',         34.0125, 35.7670),  -- was 6.2 km off
    ('Jbeil',            34.1245, 35.8112),  -- was 5.7 km off
    ('Miniyeh-Danniyeh', 34.3797, 36.0467),  -- was 6.2 km off
    ('Baalbek',          34.1057, 36.2635),  -- was 13.1 km off
    ('Hermel',           34.3865, 36.3087),  -- was 6.6 km off
    ('Sidon',            33.4487, 35.3553),  -- was 13.1 km off
    ('Jezzine',          33.4947, 35.5488),  -- was 5.8 km off
    ('Marjayoun',        33.2675, 35.5335)   -- was 11.6 km off
) AS fix(name, lat, lon)
WHERE a.name = fix.name;

DO $$
BEGIN
    IF (SELECT count(*) FROM areas WHERE name IN ('Baabda', 'Keserwan', 'Jbeil', 'Miniyeh-Danniyeh', 'Baalbek', 'Hermel', 'Sidon', 'Jezzine', 'Marjayoun')) <> 9 THEN
        RAISE EXCEPTION 'V7: expected 9 areas';
    END IF;
END
$$;
