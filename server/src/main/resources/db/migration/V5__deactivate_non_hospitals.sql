-- Hide OSM entries that aren't hospitals a patient would be admitted to, and
-- duplicates of a hospital that has a better-tagged main entry. Rows are kept
-- (is_active = false) so the osm_id stays reserved; matched by osm_id, not name.

UPDATE hospitals SET is_active = false
WHERE osm_id IN (
    -- Outbuildings of American University Hospital (node/713413597 stays)
    'way/211453309',    -- AUH Family Medicine Centre
    'node/7248082285',  -- Halim & Aida Daniel Academic and Clinical Centre
    -- Cosmetic surgery office
    'node/4547573490',  -- بناية ناصيف جراحة تجميل الدكتور عماد شحاتة
    -- Lebanese Red Cross centres
    'way/478650547',
    'way/617004379',
    -- Duplicates
    'way/585187493',    -- Clémenceau Medical Centre (main: way/566103031, Clemenceau Medical Center)
    'way/483236077'     -- Al Rassoul Al Azam (heart) (main: way/236020132, Al Rasoul Al Azam Hospital)
);

-- Fail loudly if an osm_id was mistyped or missing from the seed.
DO $$
BEGIN
    IF (SELECT count(*) FROM hospitals
        WHERE NOT is_active
          AND osm_id IN ('way/211453309', 'node/7248082285', 'node/4547573490', 'way/478650547',
                         'way/617004379', 'way/585187493', 'way/483236077')) <> 7 THEN
        RAISE EXCEPTION 'V5: expected to deactivate 7 hospitals';
    END IF;
END
$$;
