-- Caps for model-generated SQL beyond statement_timeout: a runaway sort or join must not fill the disk with
-- temp files. temp_file_limit can only be set by a superuser; on a managed database set it there.
do $$
begin
    alter role oscdb_inquiry set temp_file_limit = '1GB';
exception when insufficient_privilege then
    raise warning 'Could not set temp_file_limit for oscdb_inquiry (needs a superuser); set it manually';
end
$$;

alter role oscdb_inquiry set work_mem = '64MB';
