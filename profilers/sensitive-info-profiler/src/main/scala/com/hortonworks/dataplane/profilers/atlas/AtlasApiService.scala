/*
 *   HORTONWORKS DATAPLANE SERVICE AND ITS CONSTITUENT SERVICES
 *
 *   (c) 2016-2018 Hortonworks, Inc. All rights reserved.
 *
 *   This code is provided to you pursuant to your written agreement with Hortonworks, which may be the terms of the
 *   Affero General Public License version 3 (AGPLv3), or pursuant to a written agreement with a third party authorized
 *   to distribute this code.  If you do not have a written agreement with Hortonworks or with an authorized and
 *   properly licensed third party, you do not have any rights to this code.
 *
 *   If this code is provided to you under the terms of the AGPLv3:
 *   (A) HORTONWORKS PROVIDES THIS CODE TO YOU WITHOUT WARRANTIES OF ANY KIND;
 *   (B) HORTONWORKS DISCLAIMS ANY AND ALL EXPRESS AND IMPLIED WARRANTIES WITH RESPECT TO THIS CODE, INCLUDING BUT NOT
 *     LIMITED TO IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE;
 *   (C) HORTONWORKS IS NOT LIABLE TO YOU, AND WILL NOT DEFEND, INDEMNIFY, OR HOLD YOU HARMLESS FOR ANY CLAIMS ARISING
 *     FROM OR RELATED TO THE CODE; AND
 *   (D) WITH RESPECT TO YOUR EXERCISE OF ANY RIGHTS GRANTED TO YOU FOR THE CODE, HORTONWORKS IS NOT LIABLE FOR ANY
 *     DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, PUNITIVE OR CONSEQUENTIAL DAMAGES INCLUDING, BUT NOT LIMITED TO,
 *     DAMAGES RELATED TO LOST REVENUE, LOST PROFITS, LOSS OF INCOME, LOSS OF BUSINESS ADVANTAGE OR UNAVAILABILITY,
 *     OR LOSS OR CORRUPTION OF DATA.
 */

package com.hortonworks.dataplane.profilers.atlas

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.hortonworks.dataplane.profilers.atlas.interactors.TableEntityBuilder
import com.hortonworks.dataplane.profilers.atlas.models.CustomModels.{TableEntity, TableResultWithGuid, TagDefinition, TagDefinitionAttribute}
import com.hortonworks.dataplane.profilers.atlas.models._
import com.hortonworks.dataplane.profilers.atlas.parsers.AtlasResponseParsers._
import com.hortonworks.dataplane.profilers.atlas.parsers.CustomModelParsers._
import com.hortonworks.dataplane.profilers.commons.Constants
import com.hortonworks.dataplane.profilers.commons.Models.AtlasInfo
import play.api.libs.json.Json
import scalaj.http.Http

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AtlasApiService(atlasInfo: AtlasInfo) {


  private val atlasAuthString = "Basic " + Base64.getEncoder.encodeToString((atlasInfo.user + ":" +
    atlasInfo.password).getBytes(StandardCharsets.UTF_8))

  private val headers = List(("X-XSRF-HEADER", "valid"), ("Content-Type", "application/json"), ("Authorization", atlasAuthString))
  private val AtlasRESTEndpoint = s"${atlasInfo.url}/api/atlas/v2"


  def retrieveSimpleEntities(fullyQualifiedNameOfTables: List[String]): Future[SimpleEntityResponse] = {
    val query = EntityDefinitions("hive_table", fullyQualifiedNameOfTables)
    val queryParams = query.getQueryParams
    val url = AtlasRESTEndpoint + "/search/dsl"
    Future(Http(url).headers(headers).params(queryParams).asString).flatMap {
      resp =>
        resp.code match {
          case 200 =>
            Future.successful(Json.parse(resp.body).as[SimpleEntityResponse])
          case _ =>
            Future.failed(new Exception(s"Failed to retrieve guids from atlas , status code :${resp.code} , ${resp.body}"))
        }
    }
  }


  def retrieveDetailedEntityResponse(guids: List[String]): Future[List[TableEntity]] = {
    val guidQuery = Guids(guids)
    val queryParams = guidQuery.getQueryParams
    val url = AtlasRESTEndpoint + "/entity/bulk"
    Future(Http(url).headers(headers).params(queryParams).asString).flatMap {
      resp =>
        resp.code match {
          case 200 =>
            val entityResponse = Json.parse(resp.body).as[EntityResponse]
            Future.successful(TableEntityBuilder.buildFrom(entityResponse))
          case _ =>
            Future.failed(new Exception(s"Failed to retrieve entities from atlas , status code :${resp.code} , ${resp.body}"))
        }
    }
  }

  def getDpTags(): Future[Set[String]] = {
    val classificationUrl = AtlasRESTEndpoint + "/types/typedefs?type=classification"
    Future(Http(classificationUrl).headers(headers).asString).flatMap {
      resp =>
        resp.code match {
          case 200 =>
            val entityResponse = Json.parse(resp.body).as[ListAllTagResponse]
            Future.successful(entityResponse.classificationDefs.filter(_.superTypes.contains(Constants.DPSuperTypeTag)).map(
              _.name
            ).toSet.map((x: String) => x.replaceFirst(Constants.dpTagPrefix, "")))
          case _ =>
            Future.failed(new Exception(s"Failed to retrieve tags from atlas , status code :${resp.code} , ${resp.body}"))
        }
    }
  }

  def persistTags(results: List[TableResultWithGuid], retry: Int): Future[Unit] = {
    val guidsAndTags: Map[String, List[TagDefinition]] = results.map(rslt =>
      rslt.guid -> TagDefinition(rslt.tableResult.label, TagDefinitionAttribute(rslt.tableResult.status))
    ).groupBy(_._1).mapValues(_.map(_._2))
    val eventualUnits = guidsAndTags.map(
      guidAndTags => {
        val guid: String = guidAndTags._1
        val tags: List[TagDefinition] = guidAndTags._2
        val tagsWithDpPrefix = tags.map(tag => TagDefinition(Constants.dpTagPrefix + tag.typeName, tag.attributes))
        val url = AtlasRESTEndpoint + s"/entity/guid/$guid/classifications"
        tryPersistingTagsWithRetry(guid, tagsWithDpPrefix, url, retry)
      }
    )
    Future.sequence(eventualUnits).map(_ => Unit)
  }

  private def tryPersistingTagsWithRetry(guid: String, tags: List[TagDefinition], url: String, retry: Int): Future[Unit] = {
    Future(Http(url).headers(headers).postData(Json.stringify(Json.toJson(tags))).asString).flatMap {
      resp =>
        resp.code match {
          case x if Set(201, 204).contains(x) =>
            Future.successful()
          case _ =>
            if (retry > 0) {
              tryPersistingTagsWithRetry(guid, tags, url, retry - 1)
            }
            else {
              Future.failed(new Exception(s"Failed to persist tags in Atlas for guid $guid ," +
                s" status code :${resp.code} , ${resp.body}"))
            }
        }
    }
  }
}
