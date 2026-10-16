-- More OSM entries that aren't hospitals (found after V5). Matched by osm_id, not name.

UPDATE hospitals SET is_active = false
WHERE osm_id IN (
    'node/3068442398',  -- "red cross" (Lebanese Red Cross centre, Baabda)
    -- Primary health centres, not hospitals (Akkar)
    'way/1044458284',   -- المركز الصحي المتطور
    'node/4048571547'   -- مركز وادي خالد الصحي (Wadi Khaled health centre)
);

-- Fail loudly if an osm_id was mistyped or missing from the seed.
DO $$
BEGIN
    IF (SELECT count(*) FROM hospitals
        WHERE NOT is_active
          AND osm_id IN ('node/3068442398', 'way/1044458284', 'node/4048571547')) <> 3 THEN
        RAISE EXCEPTION 'V6: expected to deactivate 3 hospitals';
    END IF;
END
$$;
