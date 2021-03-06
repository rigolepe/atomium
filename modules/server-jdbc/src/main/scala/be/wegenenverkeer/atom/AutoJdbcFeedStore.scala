package be.wegenenverkeer.atom

import be.wegenenverkeer.atom.jdbc.{FeedDbModel, Dialect}

class AutoJdbcFeedStore [E](context: JdbcContext,
                            feedName: String,
                            title: Option[String],
                            ser: E => String,
                            deser: String => E,
                            urlBuilder: UrlBuilder) extends AbstractJdbcFeedStore[E](context, feedName, title, ser, deser, urlBuilder) {

  dialect: Dialect =>

  /**
   * The entry table name is used by the abstract JDBC feed store each time an entry table is
   * accessed, so the feed model, the feed table and the entry table will be instantiated on
   * the first use of the entry table name.
   */
  override def entryTableName: String = feedModel.autoEntryTableName

  /**
   * auto-registers the feed in FEEDS table and automatically creates the feed's entries table
   * The FeedModel is also cached, so that recreating the AutoJdbcFeedStore does not have to lookup the FeedModel from
   * the database each time.
   */
  lazy val feedModel: FeedDbModel = {
    FeedModelRegistry.getOrElseUpdate(feedName, {

      //create table FEEDS if it does not exist
      dialect.createFeedTableIfNotExists
      val feedModel = dialect.fetchFeed(feedName) match {
        case Some(feed) => feed
        case None =>
          val feed = FeedDbModel(id = None, name = feedName, title = title)
          dialect.addFeed(feed)
          feed
      }
      dialect.createEntryTableIfNotExists(feedModel.autoEntryTableName)
      feedModel
    })
  }

}

/**
 * FeedModelRegistry keeps a maps of feedName to its corresponding FeedModel
 * in order to avoid redundant queries to the DB to retrieve the FeedModel
 */
object FeedModelRegistry {

  import scala.collection.immutable.Map
  private var feedModelCache: Map[String, FeedDbModel] = Map.empty[String, FeedDbModel]

  def getOrElseUpdate(key: String, op: => FeedDbModel): FeedDbModel = {
    if (feedModelCache.contains(key)) {
      feedModelCache(key)
    } else {
      val feedModel = op
      feedModelCache += (key -> feedModel)
      feedModel
    }
  }

  def clear() = feedModelCache = Map.empty

}
