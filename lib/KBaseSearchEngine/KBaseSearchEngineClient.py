# -*- coding: utf-8 -*-
############################################################
#
# Autogenerated by the KBase type compiler -
# any changes made here will be overwritten
#
############################################################

from __future__ import print_function
# the following is a hack to get the baseclient to import whether we're in a
# package or not. This makes pep8 unhappy hence the annotations.
try:
    # baseclient and this client are in a package
    from .baseclient import BaseClient as _BaseClient  # @UnusedImport
except:
    # no they aren't
    from baseclient import BaseClient as _BaseClient  # @Reimport


class KBaseSearchEngine(object):

    def __init__(
            self, url=None, timeout=30 * 60, user_id=None,
            password=None, token=None, ignore_authrc=False,
            trust_all_ssl_certificates=False,
            auth_svc='https://kbase.us/services/authorization/Sessions/Login'):
        if url is None:
            raise ValueError('A url is required')
        self._service_ver = None
        self._client = _BaseClient(
            url, timeout=timeout, user_id=user_id, password=password,
            token=token, ignore_authrc=ignore_authrc,
            trust_all_ssl_certificates=trust_all_ssl_certificates,
            auth_svc=auth_svc)

    def search_types(self, params, context=None):
        """
        Search for number of objects of each type matching constraints.
        :param params: instance of type "SearchTypesInput" (Input parameters
           for search_types method.) -> structure: parameter "match_filter"
           of type "MatchFilter" (Optional rules of defining constrains for
           object properties including values of keywords or metadata/system
           properties (like object name, creation time range) or full-text
           search in all properties. boolean exclude_subobjects - don't
           return any subobjects in the search results if true. Default
           false. list<string> source_tags - source tags are arbitrary
           strings applied to data at the data source (for example, the
           workspace service). The source_tags list may optionally be
           populated with a set of tags that will determine what data is
           returned in a search. By default, the list behaves as a whitelist
           and only data with at least one of the tags will be returned.
           source_tags_blacklist - if true, the source_tags list behaves as a
           blacklist and any data with at least one of the tags will be
           excluded from the search results. If missing or false, the default
           behavior is maintained.) -> structure: parameter
           "full_text_in_all" of String, parameter "object_name" of String,
           parameter "timestamp" of type "MatchValue" (Optional rules of
           defining constraints for values of particular term (keyword).
           Appropriate field depends on type of keyword. For instance in case
           of integer type 'int_value' should be used. In case of range
           constraint rather than single value 'min_*' and 'max_*' fields
           should be used. You may omit one of ends of range to achieve '<='
           or '>=' comparison. Ends are always included for range
           constraints.) -> structure: parameter "value" of String, parameter
           "int_value" of Long, parameter "double_value" of Double, parameter
           "bool_value" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "min_int" of Long, parameter "max_int" of Long,
           parameter "min_date" of Long, parameter "max_date" of Long,
           parameter "min_double" of Double, parameter "max_double" of
           Double, parameter "exclude_subobjects" of type "boolean" (A
           boolean. 0 = false, other = true.), parameter "lookupInKeys" of
           mapping from String to type "MatchValue" (Optional rules of
           defining constraints for values of particular term (keyword).
           Appropriate field depends on type of keyword. For instance in case
           of integer type 'int_value' should be used. In case of range
           constraint rather than single value 'min_*' and 'max_*' fields
           should be used. You may omit one of ends of range to achieve '<='
           or '>=' comparison. Ends are always included for range
           constraints.) -> structure: parameter "value" of String, parameter
           "int_value" of Long, parameter "double_value" of Double, parameter
           "bool_value" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "min_int" of Long, parameter "max_int" of Long,
           parameter "min_date" of Long, parameter "max_date" of Long,
           parameter "min_double" of Double, parameter "max_double" of
           Double, parameter "source_tags" of list of String, parameter
           "source_tags_blacklist" of type "boolean" (A boolean. 0 = false,
           other = true.), parameter "access_filter" of type "AccessFilter"
           (Optional rules of access constraints. - with_private - include
           data found in workspaces not marked as public, default value is
           true, - with_public - include data found in public workspaces,
           default value is false, - with_all_history - include all versions
           (last one and all old versions) of objects matching constrains,
           default value is false.) -> structure: parameter "with_private" of
           type "boolean" (A boolean. 0 = false, other = true.), parameter
           "with_public" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "with_all_history" of type "boolean" (A boolean.
           0 = false, other = true.)
        :returns: instance of type "SearchTypesOutput" (Output results of
           search_types method.) -> structure: parameter "type_to_count" of
           mapping from String to Long, parameter "search_time" of Long
        """
        return self._client.call_method(
            'KBaseSearchEngine.search_types',
            [params], self._service_ver, context)

    def search_objects(self, params, context=None):
        """
        Search for objects of particular type matching constraints.
        :param params: instance of type "SearchObjectsInput" (Input
           parameters for 'search_objects' method. object_types - list of the
           types of objects to search on (optional). The function will search
           on all objects if the list is not specified or is empty. The list
           size must be less than 50. match_filter - see MatchFilter
           (optional). sorting_rules - see SortingRule (optional).
           access_filter - see AccessFilter (optional). pagination - see
           Pagination (optional). post_processing - see PostProcessing
           (optional).) -> structure: parameter "object_types" of list of
           String, parameter "match_filter" of type "MatchFilter" (Optional
           rules of defining constrains for object properties including
           values of keywords or metadata/system properties (like object
           name, creation time range) or full-text search in all properties.
           boolean exclude_subobjects - don't return any subobjects in the
           search results if true. Default false. list<string> source_tags -
           source tags are arbitrary strings applied to data at the data
           source (for example, the workspace service). The source_tags list
           may optionally be populated with a set of tags that will determine
           what data is returned in a search. By default, the list behaves as
           a whitelist and only data with at least one of the tags will be
           returned. source_tags_blacklist - if true, the source_tags list
           behaves as a blacklist and any data with at least one of the tags
           will be excluded from the search results. If missing or false, the
           default behavior is maintained.) -> structure: parameter
           "full_text_in_all" of String, parameter "object_name" of String,
           parameter "timestamp" of type "MatchValue" (Optional rules of
           defining constraints for values of particular term (keyword).
           Appropriate field depends on type of keyword. For instance in case
           of integer type 'int_value' should be used. In case of range
           constraint rather than single value 'min_*' and 'max_*' fields
           should be used. You may omit one of ends of range to achieve '<='
           or '>=' comparison. Ends are always included for range
           constraints.) -> structure: parameter "value" of String, parameter
           "int_value" of Long, parameter "double_value" of Double, parameter
           "bool_value" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "min_int" of Long, parameter "max_int" of Long,
           parameter "min_date" of Long, parameter "max_date" of Long,
           parameter "min_double" of Double, parameter "max_double" of
           Double, parameter "exclude_subobjects" of type "boolean" (A
           boolean. 0 = false, other = true.), parameter "lookupInKeys" of
           mapping from String to type "MatchValue" (Optional rules of
           defining constraints for values of particular term (keyword).
           Appropriate field depends on type of keyword. For instance in case
           of integer type 'int_value' should be used. In case of range
           constraint rather than single value 'min_*' and 'max_*' fields
           should be used. You may omit one of ends of range to achieve '<='
           or '>=' comparison. Ends are always included for range
           constraints.) -> structure: parameter "value" of String, parameter
           "int_value" of Long, parameter "double_value" of Double, parameter
           "bool_value" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "min_int" of Long, parameter "max_int" of Long,
           parameter "min_date" of Long, parameter "max_date" of Long,
           parameter "min_double" of Double, parameter "max_double" of
           Double, parameter "source_tags" of list of String, parameter
           "source_tags_blacklist" of type "boolean" (A boolean. 0 = false,
           other = true.), parameter "sorting_rules" of list of type
           "SortingRule" (Rule for sorting found results. 'key_name',
           'is_timestamp' and 'is_object_name' are alternative way of
           defining what property if used for sorting. Default order is
           ascending (if 'descending' field is not set).) -> structure:
           parameter "key_name" of String, parameter "ascending" of type
           "boolean" (A boolean. 0 = false, other = true.), parameter
           "access_filter" of type "AccessFilter" (Optional rules of access
           constraints. - with_private - include data found in workspaces not
           marked as public, default value is true, - with_public - include
           data found in public workspaces, default value is false, -
           with_all_history - include all versions (last one and all old
           versions) of objects matching constrains, default value is false.)
           -> structure: parameter "with_private" of type "boolean" (A
           boolean. 0 = false, other = true.), parameter "with_public" of
           type "boolean" (A boolean. 0 = false, other = true.), parameter
           "with_all_history" of type "boolean" (A boolean. 0 = false, other
           = true.), parameter "pagination" of type "Pagination" (Pagination
           rules. Default values are: start = 0, count = 50.) -> structure:
           parameter "start" of Long, parameter "count" of Long, parameter
           "post_processing" of type "PostProcessing" (Rules for what to
           return about found objects. skip_info - do not include brief info
           for object ('guid, 'parent_guid', 'object_name' and 'timestamp'
           fields in ObjectData structure), skip_keys - do not include
           keyword values for object ('key_props' field in ObjectData
           structure), skip_data - do not include raw data for object ('data'
           and 'parent_data' fields in ObjectData structure),
           include_highlight - include highlights of fields that matched
           query, ids_only - shortcut to mark all three skips as true and
           include_highlight as false.) -> structure: parameter "ids_only" of
           type "boolean" (A boolean. 0 = false, other = true.), parameter
           "skip_info" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "skip_keys" of type "boolean" (A boolean. 0 =
           false, other = true.), parameter "skip_data" of type "boolean" (A
           boolean. 0 = false, other = true.), parameter "include_highlight"
           of type "boolean" (A boolean. 0 = false, other = true.), parameter
           "data_includes" of list of String
        :returns: instance of type "SearchObjectsOutput" (Output results for
           'search_objects' method. 'pagination' and 'sorting_rules' fields
           show actual input for pagination and sorting. total - total number
           of found objects. search_time - common time in milliseconds spent.
           mapping<access_group_id, narrative_info>
           access_group_narrative_info - information about the workspaces in
           which the objects in the results reside. This data only applies to
           workspace objects.) -> structure: parameter "pagination" of type
           "Pagination" (Pagination rules. Default values are: start = 0,
           count = 50.) -> structure: parameter "start" of Long, parameter
           "count" of Long, parameter "sorting_rules" of list of type
           "SortingRule" (Rule for sorting found results. 'key_name',
           'is_timestamp' and 'is_object_name' are alternative way of
           defining what property if used for sorting. Default order is
           ascending (if 'descending' field is not set).) -> structure:
           parameter "key_name" of String, parameter "ascending" of type
           "boolean" (A boolean. 0 = false, other = true.), parameter
           "objects" of list of type "ObjectData" (Properties of found object
           including metadata, raw data and keywords. mapping<string,
           list<string>> highlight - The keys are the field names and the
           list contains the sections in each field that matched the search
           query. Fields with no hits will not be available. Short fields
           that matched are shown in their entirety. Longer fields are shown
           as snippets preceded or followed by "...". mapping<string, string>
           object_props - general properties for all objects. This mapping
           contains the keys 'creator', 'copied', 'module', 'method',
           'module_ver', and 'commit' - respectively the user that originally
           created the object, the user that copied this incarnation of the
           object, and the module and method used to create the object and
           their version and version control commit hash. Not all keys may be
           present; if not their values were not available in the search
           data.) -> structure: parameter "guid" of type "GUID" (Global user
           identificator. It has structure like this:
           <data-source-code>:<full-reference>[:<sub-type>/<sub-id>]),
           parameter "parent_guid" of type "GUID" (Global user identificator.
           It has structure like this:
           <data-source-code>:<full-reference>[:<sub-type>/<sub-id>]),
           parameter "object_name" of String, parameter "timestamp" of Long,
           parameter "parent_data" of unspecified object, parameter "data" of
           unspecified object, parameter "key_props" of mapping from String
           to String, parameter "object_props" of mapping from String to
           String, parameter "highlight" of mapping from String to list of
           String, parameter "total" of Long, parameter "search_time" of
           Long, parameter "access_group_narrative_info" of mapping from type
           "access_group_id" (A data source access group ID (for instance,
           the integer ID of a workspace).) to type "narrative_info"
           (Information about a workspace, which may or may not contain a
           KBase Narrative. This data is specific for data from the Workspace
           Service. string narrative_name - the name of the narrative
           contained in the workspace, or null if the workspace does not
           contain a narrative. int narrative_id - the id of the narrative
           contained in the workspace, or null. timestamp time_last_saved -
           the modification date of the workspace. string ws_owner_username -
           the unique user name of the workspace's owner. string
           ws_owner_displayname - the display name of the workspace's owner.)
           -> tuple of size 5: parameter "narrative_name" of String,
           parameter "narrative_id" of Long, parameter "time_last_saved" of
           type "timestamp" (A timestamp in milliseconds since the epoch.),
           parameter "ws_owner_username" of String, parameter
           "ws_owner_displayname" of String
        """
        return self._client.call_method(
            'KBaseSearchEngine.search_objects',
            [params], self._service_ver, context)

    def get_objects(self, params, context=None):
        """
        Retrieve objects by their GUIDs.
        :param params: instance of type "GetObjectsInput" (Input parameters
           for get_objects method.) -> structure: parameter "guids" of list
           of type "GUID" (Global user identificator. It has structure like
           this: <data-source-code>:<full-reference>[:<sub-type>/<sub-id>]),
           parameter "post_processing" of type "PostProcessing" (Rules for
           what to return about found objects. skip_info - do not include
           brief info for object ('guid, 'parent_guid', 'object_name' and
           'timestamp' fields in ObjectData structure), skip_keys - do not
           include keyword values for object ('key_props' field in ObjectData
           structure), skip_data - do not include raw data for object ('data'
           and 'parent_data' fields in ObjectData structure),
           include_highlight - include highlights of fields that matched
           query, ids_only - shortcut to mark all three skips as true and
           include_highlight as false.) -> structure: parameter "ids_only" of
           type "boolean" (A boolean. 0 = false, other = true.), parameter
           "skip_info" of type "boolean" (A boolean. 0 = false, other =
           true.), parameter "skip_keys" of type "boolean" (A boolean. 0 =
           false, other = true.), parameter "skip_data" of type "boolean" (A
           boolean. 0 = false, other = true.), parameter "include_highlight"
           of type "boolean" (A boolean. 0 = false, other = true.), parameter
           "data_includes" of list of String
        :returns: instance of type "GetObjectsOutput" (Output results of
           get_objects method. mapping<access_group_id, narrative_info>
           access_group_narrative_info - information about the workspaces in
           which the objects in the results reside. This data only applies to
           workspace objects.) -> structure: parameter "objects" of list of
           type "ObjectData" (Properties of found object including metadata,
           raw data and keywords. mapping<string, list<string>> highlight -
           The keys are the field names and the list contains the sections in
           each field that matched the search query. Fields with no hits will
           not be available. Short fields that matched are shown in their
           entirety. Longer fields are shown as snippets preceded or followed
           by "...". mapping<string, string> object_props - general
           properties for all objects. This mapping contains the keys
           'creator', 'copied', 'module', 'method', 'module_ver', and
           'commit' - respectively the user that originally created the
           object, the user that copied this incarnation of the object, and
           the module and method used to create the object and their version
           and version control commit hash. Not all keys may be present; if
           not their values were not available in the search data.) ->
           structure: parameter "guid" of type "GUID" (Global user
           identificator. It has structure like this:
           <data-source-code>:<full-reference>[:<sub-type>/<sub-id>]),
           parameter "parent_guid" of type "GUID" (Global user identificator.
           It has structure like this:
           <data-source-code>:<full-reference>[:<sub-type>/<sub-id>]),
           parameter "object_name" of String, parameter "timestamp" of Long,
           parameter "parent_data" of unspecified object, parameter "data" of
           unspecified object, parameter "key_props" of mapping from String
           to String, parameter "object_props" of mapping from String to
           String, parameter "highlight" of mapping from String to list of
           String, parameter "search_time" of Long, parameter
           "access_group_narrative_info" of mapping from type
           "access_group_id" (A data source access group ID (for instance,
           the integer ID of a workspace).) to type "narrative_info"
           (Information about a workspace, which may or may not contain a
           KBase Narrative. This data is specific for data from the Workspace
           Service. string narrative_name - the name of the narrative
           contained in the workspace, or null if the workspace does not
           contain a narrative. int narrative_id - the id of the narrative
           contained in the workspace, or null. timestamp time_last_saved -
           the modification date of the workspace. string ws_owner_username -
           the unique user name of the workspace's owner. string
           ws_owner_displayname - the display name of the workspace's owner.)
           -> tuple of size 5: parameter "narrative_name" of String,
           parameter "narrative_id" of Long, parameter "time_last_saved" of
           type "timestamp" (A timestamp in milliseconds since the epoch.),
           parameter "ws_owner_username" of String, parameter
           "ws_owner_displayname" of String
        """
        return self._client.call_method(
            'KBaseSearchEngine.get_objects',
            [params], self._service_ver, context)

    def list_types(self, params, context=None):
        """
        List registered searchable object types.
        :param params: instance of type "ListTypesInput" (Input parameters
           for list_types method. type_name - optional parameter; if not
           specified all types are described.) -> structure: parameter
           "type_name" of String
        :returns: instance of type "ListTypesOutput" (Output results of
           list_types method.) -> structure: parameter "types" of mapping
           from String to type "TypeDescriptor" (Description of searchable
           object type including details about keywords. TODO: add more
           details like parent type, primary key, ...) -> structure:
           parameter "type_name" of String, parameter "type_ui_title" of
           String, parameter "keys" of list of type "KeyDescription"
           (Description of searchable type keyword. - key_value_type can be
           one of {'string', 'integer', 'double', 'boolean'}, - hidden - if
           true then this keyword provides values for other keywords (like in
           'link_key') and is not supposed to be shown. - link_key - optional
           field pointing to another keyword (which is often hidden)
           providing GUID to build external URL to.) -> structure: parameter
           "key_name" of String, parameter "key_ui_title" of String,
           parameter "key_value_type" of String, parameter "hidden" of type
           "boolean" (A boolean. 0 = false, other = true.), parameter
           "link_key" of String
        """
        return self._client.call_method(
            'KBaseSearchEngine.list_types',
            [params], self._service_ver, context)

    def status(self, context=None):
        return self._client.call_method('KBaseSearchEngine.status',
                                        [], self._service_ver, context)
