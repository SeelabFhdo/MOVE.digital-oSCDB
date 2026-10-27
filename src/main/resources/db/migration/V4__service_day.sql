-- One row per date a service runs: calendar weekday flags within range, plus calendar_dates type 1,
-- minus type 2. Materialized at import so queries never re-implement the GTFS calendar rules.
create table service_day (
    feed_id      int  not null references feed (id) on delete cascade,
    service_id   text not null,
    service_date date not null,
    primary key (feed_id, service_id, service_date)
);

create index service_day_date_idx on service_day (service_date);

create function fill_service_days(p_feed int) returns void language sql as $$
    insert into service_day (feed_id, service_id, service_date)
    select c.feed_id, c.service_id, d::date
    from calendar c cross join lateral generate_series(c.start_date, c.end_date, interval '1 day') d
    where c.feed_id = p_feed
      and case extract(isodow from d)
            when 1 then c.monday when 2 then c.tuesday when 3 then c.wednesday when 4 then c.thursday
            when 5 then c.friday when 6 then c.saturday else c.sunday end
    union
    select feed_id, service_id, date from calendar_date where feed_id = p_feed and exception_type = 1
    except
    select feed_id, service_id, date from calendar_date where feed_id = p_feed and exception_type = 2;
$$;

select fill_service_days(id) from feed;
