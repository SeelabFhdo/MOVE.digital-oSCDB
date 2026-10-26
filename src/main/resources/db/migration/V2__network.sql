create extension if not exists pg_trgm;

create table agency (
    feed_id   int  not null references feed (id) on delete cascade,
    agency_id text not null,
    name      text not null,
    url       text,
    timezone  text,
    lang      text,
    phone     text,
    fare_url  text,
    primary key (feed_id, agency_id)
);

create table route (
    feed_id    int  not null references feed (id) on delete cascade,
    route_id   text not null,
    agency_id  text,
    short_name text,
    long_name  text,
    type       int,
    color      text,
    text_color text,
    primary key (feed_id, route_id)
);

create table station (
    feed_id    int  not null references feed (id) on delete cascade,
    station_id text not null,
    name       text not null,
    lat        double precision not null,
    lon        double precision not null,
    primary key (feed_id, station_id)
);

create table stop (
    feed_id             int  not null references feed (id) on delete cascade,
    stop_id             text not null,
    code                text,
    name                text,
    lat                 double precision,
    lon                 double precision,
    location_type       int,
    parent_station      text,
    wheelchair_boarding int,
    station_id          text,
    primary key (feed_id, stop_id),
    foreign key (feed_id, station_id) references station (feed_id, station_id)
);

create index stop_station_idx on stop (feed_id, station_id);
create index stop_name_trgm_idx on stop using gin (lower(name) gin_trgm_ops);
create index station_name_trgm_idx on station using gin (lower(name) gin_trgm_ops);
