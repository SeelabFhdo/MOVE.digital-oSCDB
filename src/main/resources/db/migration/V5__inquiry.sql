-- inquiry module: job table, curated read-only views for model-generated SQL, and the restricted role
-- that runs that SQL. The views are the only place where inquiry reads tables of feed/network/schedule.

create table inquiry (
    id          uuid primary key,
    seq         bigint generated always as identity,
    question    text        not null,
    format      text        not null,
    status      text        not null check (status in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    created_at  timestamptz not null,
    started_at  timestamptz,
    finished_at timestamptz,
    meta        jsonb,
    error       text,
    result      text
);

create index inquiry_queue_idx on inquiry (status, seq);
create index inquiry_created_idx on inquiry (created_at);

create schema inquiry_api;

-- Feeds -------------------------------------------------------------------------------------------

create view inquiry_api.v_feed as
select f.id as feed_id, f.source_file, f.start_date, f.end_date, f.version, f.imported_at
from feed f
where f.status = 'READY';

comment on view inquiry_api.v_feed is 'Timetable periods (GTFS feeds). Every other view carries feed_id; ids of stops, routes, trips are unique only within one feed.';
comment on column inquiry_api.v_feed.feed_id is 'Feed id';
comment on column inquiry_api.v_feed.source_file is 'Imported zip file name';
comment on column inquiry_api.v_feed.start_date is 'First day the timetable is valid';
comment on column inquiry_api.v_feed.end_date is 'Last day the timetable is valid';
comment on column inquiry_api.v_feed.version is 'Export timestamp of the feed, not a timetable version';
comment on column inquiry_api.v_feed.imported_at is 'When the feed was imported';

create view inquiry_api.v_feed_day as
select distinct on (d::date) d::date as service_date, f.id as feed_id
from feed f cross join lateral generate_series(f.start_date, f.end_date, interval '1 day') d
where f.status = 'READY'
order by d::date, f.imported_at desc nulls last, f.id desc;

comment on view inquiry_api.v_feed_day is 'For each calendar date, the one feed that is valid on that date (if feeds overlap, the newest import wins).';
comment on column inquiry_api.v_feed_day.service_date is 'Calendar date';
comment on column inquiry_api.v_feed_day.feed_id is 'Feed valid on that date';

-- Network -----------------------------------------------------------------------------------------

create view inquiry_api.v_agency as
select a.feed_id, a.agency_id, a.name, a.url, a.timezone
from agency a join feed f on f.id = a.feed_id and f.status = 'READY';

comment on view inquiry_api.v_agency is 'Transport operators per feed.';
comment on column inquiry_api.v_agency.feed_id is 'Feed id';
comment on column inquiry_api.v_agency.agency_id is 'Agency id (unique per feed)';
comment on column inquiry_api.v_agency.name is 'Agency name, e.g. DSW21';
comment on column inquiry_api.v_agency.url is 'Agency website';
comment on column inquiry_api.v_agency.timezone is 'Agency time zone';

create view inquiry_api.v_route as
select r.feed_id, r.route_id, r.short_name as line, r.long_name,
       case r.type when 0 then 'tram' when 1 then 'subway' when 2 then 'rail' when 3 then 'bus'
                   when 405 then 'monorail' else 'other' end as route_type,
       r.type as route_type_code, r.color, r.agency_id, a.name as agency_name
from route r
join feed f on f.id = r.feed_id and f.status = 'READY'
left join agency a on a.feed_id = r.feed_id and a.agency_id = r.agency_id;

comment on view inquiry_api.v_route is 'Lines (GTFS routes) per feed.';
comment on column inquiry_api.v_route.feed_id is 'Feed id';
comment on column inquiry_api.v_route.route_id is 'Route id (unique per feed; use line for display and matching)';
comment on column inquiry_api.v_route.line is 'Line name as passengers know it, e.g. U47, 440';
comment on column inquiry_api.v_route.long_name is 'Long route name (often empty)';
comment on column inquiry_api.v_route.route_type is 'tram, subway (U-Bahn/Stadtbahn), rail, bus, monorail (H-Bahn) or other';
comment on column inquiry_api.v_route.route_type_code is 'Raw GTFS route_type code';
comment on column inquiry_api.v_route.color is 'Line color as hex without #';
comment on column inquiry_api.v_route.agency_id is 'Operating agency id';
comment on column inquiry_api.v_route.agency_name is 'Operating agency name';

create view inquiry_api.v_station as
select st.feed_id, st.station_id, st.name, st.lat, st.lon,
       (select count(*) from stop s where s.feed_id = st.feed_id and s.station_id = st.station_id)::int as platform_count
from station st join feed f on f.id = st.feed_id and f.status = 'READY';

comment on view inquiry_api.v_station is 'Stations: platforms grouped by name (and distance). station_id is a slug like adelenstrasse and stays the same across feeds.';
comment on column inquiry_api.v_station.feed_id is 'Feed id';
comment on column inquiry_api.v_station.station_id is 'Station id slug, stable across feeds';
comment on column inquiry_api.v_station.name is 'Station name';
comment on column inquiry_api.v_station.lat is 'Latitude (WGS84), mean of its platforms';
comment on column inquiry_api.v_station.lon is 'Longitude (WGS84), mean of its platforms';
comment on column inquiry_api.v_station.platform_count is 'Number of platforms (stops) of the station';

create view inquiry_api.v_stop as
select s.feed_id, s.stop_id, s.name, s.code, s.lat, s.lon, s.station_id, st.name as station_name, s.wheelchair_boarding
from stop s
join feed f on f.id = s.feed_id and f.status = 'READY'
left join station st on st.feed_id = s.feed_id and st.station_id = s.station_id;

comment on view inquiry_api.v_stop is 'Stops = individual platforms. Several stops share one name and station.';
comment on column inquiry_api.v_stop.feed_id is 'Feed id';
comment on column inquiry_api.v_stop.stop_id is 'Stop id (unique per feed)';
comment on column inquiry_api.v_stop.name is 'Stop name, e.g. Dortmund Hbf';
comment on column inquiry_api.v_stop.code is 'Stop code (often empty)';
comment on column inquiry_api.v_stop.lat is 'Latitude (WGS84)';
comment on column inquiry_api.v_stop.lon is 'Longitude (WGS84)';
comment on column inquiry_api.v_stop.station_id is 'Station the platform belongs to';
comment on column inquiry_api.v_stop.station_name is 'Name of that station';
comment on column inquiry_api.v_stop.wheelchair_boarding is 'GTFS wheelchair_boarding: 0/empty unknown, 1 accessible, 2 not accessible';

-- Schedule ----------------------------------------------------------------------------------------

create view inquiry_api.v_trip as
select t.feed_id, t.trip_id, t.route_id, r.short_name as line,
       case r.type when 0 then 'tram' when 1 then 'subway' when 2 then 'rail' when 3 then 'bus'
                   when 405 then 'monorail' else 'other' end as route_type,
       coalesce(nullif(t.headsign, ''), t.derived_headsign) as headsign, t.direction_id, t.service_id
from trip t
join feed f on f.id = t.feed_id and f.status = 'READY'
join route r on r.feed_id = t.feed_id and r.route_id = t.route_id;

comment on view inquiry_api.v_trip is 'Trips: one vehicle run along a line. Which days a trip runs: join v_service_day on (feed_id, service_id).';
comment on column inquiry_api.v_trip.feed_id is 'Feed id';
comment on column inquiry_api.v_trip.trip_id is 'Trip id (unique per feed)';
comment on column inquiry_api.v_trip.route_id is 'Route id';
comment on column inquiry_api.v_trip.line is 'Line name, e.g. U47';
comment on column inquiry_api.v_trip.route_type is 'tram, subway, rail, bus, monorail or other';
comment on column inquiry_api.v_trip.headsign is 'Destination shown on the vehicle (name of the last stop)';
comment on column inquiry_api.v_trip.direction_id is 'GTFS direction 0/1';
comment on column inquiry_api.v_trip.service_id is 'Service (operating calendar) id';

create view inquiry_api.v_service_day as
select sd.feed_id, sd.service_id, sd.service_date, coalesce(fd.feed_id = sd.feed_id, true) as effective
from service_day sd
join feed f on f.id = sd.feed_id and f.status = 'READY'
left join inquiry_api.v_feed_day fd on fd.service_date = sd.service_date;

comment on view inquiry_api.v_service_day is 'Dates on which a service runs (calendar and exceptions already applied).';
comment on column inquiry_api.v_service_day.feed_id is 'Feed id';
comment on column inquiry_api.v_service_day.service_id is 'Service id';
comment on column inquiry_api.v_service_day.service_date is 'Service day the service runs on';
comment on column inquiry_api.v_service_day.effective is 'true if this feed is the valid one on service_date. Filter effective = true unless comparing feeds or a feed is named.';

create view inquiry_api.v_departure as
select st.feed_id, sd.service_date, sd.effective,
       st.trip_id, st.stop_sequence, st.stop_id, s.name as stop_name, s.station_id, stn.name as station_name,
       s.lat, s.lon, t.route_id, r.short_name as line,
       case r.type when 0 then 'tram' when 1 then 'subway' when 2 then 'rail' when 3 then 'bus'
                   when 405 then 'monorail' else 'other' end as route_type,
       t.direction_id, coalesce(nullif(t.headsign, ''), t.derived_headsign) as headsign,
       st.departure_secs, st.arrival_secs,
       lpad((st.departure_secs / 3600)::text, 2, '0') || ':' || lpad((st.departure_secs / 60 % 60)::text, 2, '0') || ':'
           || lpad((st.departure_secs % 60)::text, 2, '0') as departure_time,
       lpad((st.arrival_secs / 3600)::text, 2, '0') || ':' || lpad((st.arrival_secs / 60 % 60)::text, 2, '0') || ':'
           || lpad((st.arrival_secs % 60)::text, 2, '0') as arrival_time,
       ((sd.service_date + time '12:00') at time zone 'Europe/Berlin') - interval '12 hours'
           + make_interval(secs => st.departure_secs) as departure_at,
       ((sd.service_date + time '12:00') at time zone 'Europe/Berlin') - interval '12 hours'
           + make_interval(secs => st.arrival_secs) as arrival_at,
       st.stop_sequence = (select min(x.stop_sequence) from stop_time x where x.feed_id = st.feed_id and x.trip_id = st.trip_id) as is_first_stop,
       st.stop_sequence = (select max(x.stop_sequence) from stop_time x where x.feed_id = st.feed_id and x.trip_id = st.trip_id) as is_last_stop,
       st.pickup_type, st.drop_off_type
from stop_time st
join trip t on t.feed_id = st.feed_id and t.trip_id = st.trip_id
join inquiry_api.v_service_day sd on sd.feed_id = t.feed_id and sd.service_id = t.service_id
join stop s on s.feed_id = st.feed_id and s.stop_id = st.stop_id
join route r on r.feed_id = t.feed_id and r.route_id = t.route_id
left join station stn on stn.feed_id = s.feed_id and stn.station_id = s.station_id;

comment on view inquiry_api.v_departure is 'One row per scheduled stop of a trip on a service day. Always filter service_date (and usually effective = true); the view spans all feeds and days.';
comment on column inquiry_api.v_departure.feed_id is 'Feed id';
comment on column inquiry_api.v_departure.service_date is 'GTFS service day. Times after midnight (e.g. 24:30:00) belong to the previous service_date.';
comment on column inquiry_api.v_departure.effective is 'true if this feed is the valid one on service_date. Filter effective = true unless comparing feeds or a feed is named.';
comment on column inquiry_api.v_departure.trip_id is 'Trip id';
comment on column inquiry_api.v_departure.stop_sequence is 'Position of the stop within the trip';
comment on column inquiry_api.v_departure.stop_id is 'Stop (platform) id';
comment on column inquiry_api.v_departure.stop_name is 'Stop name';
comment on column inquiry_api.v_departure.station_id is 'Station id slug';
comment on column inquiry_api.v_departure.station_name is 'Station name';
comment on column inquiry_api.v_departure.lat is 'Stop latitude';
comment on column inquiry_api.v_departure.lon is 'Stop longitude';
comment on column inquiry_api.v_departure.route_id is 'Route id';
comment on column inquiry_api.v_departure.line is 'Line name, e.g. U47';
comment on column inquiry_api.v_departure.route_type is 'tram, subway, rail, bus, monorail or other';
comment on column inquiry_api.v_departure.direction_id is 'GTFS direction 0/1';
comment on column inquiry_api.v_departure.headsign is 'Destination of the trip';
comment on column inquiry_api.v_departure.departure_secs is 'Departure as seconds after service-day start (may exceed 86400)';
comment on column inquiry_api.v_departure.arrival_secs is 'Arrival as seconds after service-day start (may exceed 86400)';
comment on column inquiry_api.v_departure.departure_time is 'Departure as HH:MM:SS of the service day; hours can exceed 24';
comment on column inquiry_api.v_departure.arrival_time is 'Arrival as HH:MM:SS of the service day; hours can exceed 24';
comment on column inquiry_api.v_departure.departure_at is 'Real departure date and time (timestamptz, Europe/Berlin). Use this for clock times and real calendar dates.';
comment on column inquiry_api.v_departure.arrival_at is 'Real arrival date and time (timestamptz, Europe/Berlin)';
comment on column inquiry_api.v_departure.is_first_stop is 'true at the first stop of the trip';
comment on column inquiry_api.v_departure.is_last_stop is 'true at the last stop of the trip (arrival only, no departure for passengers)';
comment on column inquiry_api.v_departure.pickup_type is 'GTFS pickup_type: 0/empty regular, 1 no pickup';
comment on column inquiry_api.v_departure.drop_off_type is 'GTFS drop_off_type: 0/empty regular, 1 no drop-off';

-- Helpers -----------------------------------------------------------------------------------------

create function inquiry_api.distance_m(lat1 float8, lon1 float8, lat2 float8, lon2 float8) returns float8
    language sql immutable as $$
    select 2 * 6371000 * asin(sqrt(
        power(sin(radians(lat2 - lat1) / 2), 2)
        + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2)))
$$;

comment on function inquiry_api.distance_m(float8, float8, float8, float8) is 'Great-circle distance in metres between two WGS84 points: distance_m(lat1, lon1, lat2, lon2)';

-- Restricted role ---------------------------------------------------------------------------------
-- The password is set by the application at startup (oscdb.inquiry.datasource.password).

do $$
begin
    if not exists (select from pg_roles where rolname = 'oscdb_inquiry') then
        create role oscdb_inquiry login;
    end if;
end
$$;

grant usage on schema inquiry_api to oscdb_inquiry;
grant select on all tables in schema inquiry_api to oscdb_inquiry;
grant execute on function inquiry_api.distance_m(float8, float8, float8, float8) to oscdb_inquiry;

alter role oscdb_inquiry set default_transaction_read_only = on;
alter role oscdb_inquiry set statement_timeout = '30s';
alter role oscdb_inquiry set search_path = inquiry_api;
alter role oscdb_inquiry set timezone = 'Europe/Berlin';
