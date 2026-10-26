create table feed (
    id          serial primary key,
    source_file text not null,
    file_sha256 text not null unique,
    publisher   text,
    start_date  date not null,
    end_date    date not null,
    version     text,
    imported_at timestamptz,
    status      text not null check (status in ('IMPORTING', 'READY', 'FAILED'))
);
