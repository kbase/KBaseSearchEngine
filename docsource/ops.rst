Operations
==============

[TODO: A future PR will allow for search type versions to be alpha-numeric so we can have index names like genome_1_r1]

[TODO: A future PR will implement a CLI for trickle updates from a user specified time]

[TODO: A future PR will include strict dynamic mapping as a safety measure]

[TODO: A future PR will include html documentation]

Reindexing Naming Conventions
------------------------------

For each type of index, "genome" for example, we may have multiple indices in ElasticSearch with the naming convention "kbase.genome_1", "kbase.genome_2" etc. where the namespace "kbase" comes from the "elastic-namespace" key-value pair in search_tools.cfg and each numeric suffix comes from the search type version (See resources/typemappings/GenomeAndAssembly.yaml.example).

In order to make it easy for the client to search across all indices of a certain type, aliases are applied with the following naming convention.

.. code-block:: text

 alias -> [indices]
 genome -> [genome_1, genome_2 ... genome_n]

When reindexing is necessary, the index is reindexed to a new index with a new search type version. The first reindexing operation performed on genome_1 will result in a new index genome_1_r1 that replaces genome_1 in the genome alias. n reindexing operations performed on genome_1 will result in a new index genome_1_rn that replaces index genome_1_rn-1 in the genome alias.

Reindexing Process
-------------------
For the sake of simplicity and for the reasons described below. a single process has been defined for all reindexing cases -

a) change field value
b) add field type
c) change field type
d) remove field type

The process involves reindexing an existing index into a new index for all of these cases. i.e. in-place reindexing is discouraged because the system may not have a recent snapshot/backup or any snapshot for recovery purposes should anything go wrong with the reindexing process.

In addition, given that there can be as many as a thousand indices for KBase data, maintenance can become a challenge if the process is not simple. Some level of simplicity has been achieved here by defiining a single process that covers all the reindexing cases. If necessary, the process may be further simplified through some level of automation as it matures over time.

.. note::

    The commands below can be copy-pasted into Kibana and executed against the index. The corresponding curl commands can be obtained from Kibana by clicking on the little wrench icon that appears next to the pasted command.

.. note::

    If any of the steps below fail, don't proceed until the issue is resolved by referring to the ElasticSearch documentation.

1. Note current time

2. Stop any workers that are performing trickle updates on the index that needs to be reindexed.

3. Refresh the index that needs reindexing to make sure it has been brought to a consistent state.

.. code-block:: bash

    POST /kbase.genome_1/_refresh

4. Get a checksum for the index and record it in a separate file for later verification.

.. code-block:: bash

    GET /kbase.genome_1/_stats/docs,store

5a. If mapping needs to be changed (for cases b, c, d above), get current index mapping.

.. code-block:: bash

    GET kbase.genome_1/_mapping

5b. Copy-paste the mapping from the current index into the body section of the PUT command below and make the necessary field change (preferably one change per complete reindexing operation).

It is a good practice to make the mapping strict ("dynamic": "strict") for each type (data and access) in the index. Strict mappings prevent the mapping from being modified dynamically during ingest time.

Update the settings section below the mapping. The number of shards and replicas must be decided on based on your capacity planning rules. It is costly to change the number of shards, so choose wisely! Make sure not to exceed 600 shards for any node in the system. Increase number of replicas to improve availability.

.. code-block:: bash

    PUT kbase.genome_1_r1
    {
      "mappings": {
        "data": {
          "dynamic": "strict",
          "_parent": {
            "type": "access"
          },
          "_routing": {
            "required": true
          },
          "properties": {
            "accgrp": {
              "type": "integer"
            },
            . . .
          }
        },
        "access": {
          "dynamic": "strict",
          "properties": {
            "extpub": {
            "type": "integer"
            },
            . . .
          }
        }
      },
      "settings": {
        "index": {
          "number_of_shards": "5",
          "number_of_replicas": "1"
        }
      }
    }

5c. If the mapping does not require any change but the document's meta data needs to be changed, use the `Painless <https://www.elastic.co/guide/en/elasticsearch/reference/5.4/modules-scripting-painless-syntax.html>`_ script to modify metadata. Setting version_type to external will cause Elasticsearch to preserve the version from the source, create any documents that are missing, and update any documents that have an older version in the destination index than they do in the source index.

.. code-block:: bash

    POST _reindex
    {
      "source": {
        "index": "kbase.genome_1"
      },
      "dest": {
        "index": "kbase.genome_1_r1",
        "version_type": "external"
      },
      "script": {
        "lang": "painless",
        "inline": "if (ctx._source.foo == 'bar') {ctx._version++; ctx._source.remove('foo')}"
      }
    }

6. Now, reindex the entire data from current index to new index. Alternately, use a query to reindex only a subset of the current index.

.. code-block:: bash

    POST _reindex
    {
      "source": {
        "index": "kbase.genome_1"
      },
      "dest": {
        "index": "kbase.genome_1_r1"
      }
    }

        OR

    POST _reindex
    {
      "source": {
        "index": "kbase.genome_1",
        "query": {
          ...
        }
      },
      "dest": {
        "index": "kbase.genome_1_r1"
      }
    }

7. Run a checksum on the new index to make sure the numbers line up with the numbers of the current index.

.. code-block:: bash

    GET /kbase.genome_1_r1/_stats/docs,store

8. Run a query to specifically check the change that was applied.

.. code-block:: bash

    GET kbase.genome_1_r1/_search

   OR

    GET kbase.genome_1_r1/_search
    {
     "query": {
       "match": {
         "FIELD": "VALUE"
       }
     }
    }

   OR

    https://www.elastic.co/guide/en/elasticsearch/reference/5.5/search-request-body.html

9. If the new index looks good, update index alias and delete current index.

.. note::

    If you want the current index to linger for a day or two to serve a rollback option, reindex the current index into another new index called kbase.genome_1_backup and then delete the current index. This is one of two ways of renaming an index in ElasticSearch. The other way is to use the snapshot API.

.. code-block:: bash

    POST _aliases
    {
     "actions": [
     {
       "add": {
         "index": "kbase.genome_1_r1",
         "alias": "kbase.genome"
         }
       },
       {
         "remove": {
         "index": "kbase.genome_1",
         "alias": "kbase.genome"
       }
     }
     ]
    }

    DELETE kbase.genome_1

10. List all available indexes for the genome alias and all available genome indexes to ensure consistency across the alias map. Verify that all genome indexes that are present (except for backups) are referenced by the alias. Also verify that the alias does not contain an index reference for which no index exists.

.. code-block:: bash

    GET /_cat/aliases/kbase.genome

    GET /_cat/indices/kbase.genome_*

11. If the change involved in the reindexing operation also requires a corresponding search type spec change (located in resources/types/genome.yml for example), then this change must be applied.

12. Change mapping version from "1" to "_r1" in the resources/types/genome.yml search type spec and add a comment (for future reference) that describes the change that took place in the r1 reindexing operation.

13. Restart trickle updates from the current time noted in step 1.

