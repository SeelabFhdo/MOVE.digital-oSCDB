create table calendar (
    feed_id    int  not null references feed (id) on delete cascade,
    service_id text not null,
    start_date date not null,
    end_date   date not null,
    monday     boolean not null,
    tuesday    boolean not null,
    wednesday  boolean not null,
    thursday   boolean not null,
    friday     boolean not null,
    saturday   boolean not null,
    sunday     boolean not null,
    primary key (feed_id, service_id)
);

create table calendar_date (
    feed_id        int  not null references feed (id) on delete cascade,
    service_id     text not null,
    date           date not null,
    exception_type int  not null,
    primary key (feed_id, service_id, date)
);

create table trip (
    feed_id               int  not null references feed (id) on delete cascade,
    trip_id               text not null,
    route_id              text not null,
    service_id            text not null,
    headsign              text,
    derived_headsign      text,
    direction_id          int,
    block_id              text,
    wheelchair_accessible int,
    primary key (feed_id, trip_id)
);

create index trip_service_idx on trip (feed_id, service_id);

create table stop_time (
    feed_id        int  not null references feed (id) on delete cascade,
    trip_id        text not null,
    stop_sequence  int  not null,
    stop_id        text not null,
    arrival_secs   int,
    departure_secs int,
    pickup_type    int,
    drop_off_type  int,
    primary key (feed_id, trip_id, stop_sequence)
);

create index stop_time_stop_dep_idx on stop_time (feed_id, stop_id, departure_secs);
