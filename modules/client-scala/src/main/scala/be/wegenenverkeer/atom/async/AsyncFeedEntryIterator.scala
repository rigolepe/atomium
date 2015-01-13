package be.wegenenverkeer.atom.async

import be.wegenenverkeer.atom._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.{Failure, Success}

class AsyncFeedEntryIterator[E] (feedProvider: AsyncFeedProvider[E], timeout:Duration)(implicit val execCtx:ExecutionContext) {

  type EntryType = E
  type Entries = List[Entry[EntryType]]

  private var futureCursor: Future[Cursor] = InitCursor(feedProvider.initialEntryRef).nextCursor

  def hasNext: Future[Boolean] = {

    futureCursor.map {
      case _:EndOfEntries => false
      case _ => true
    }
  }

  def next(): Entry[E] = {

    // future must be completed
    futureCursor.value match {

      case Some(Success(entryPointer:EntryPointer)) =>
        // start the next cursor
        futureCursor = entryPointer.nextCursor
        entryPointer.currentEntry

      case Some(Success(end:EndOfEntries)) =>
        throw new NoSuchElementException("next() called on a terminated iterator ")

      case Some(Failure(e)) => throw e

      case None =>
        throw new IllegalStateException("next() called on a not-completed async iterator")

      // being exhaustive to make the compiler happy
      case Some(Success(anyOther)) =>
        throw new IllegalStateException(s"next() called while iterator cursor of type $anyOther")
    }

  }

  /** Internal pointer to the current entry. */
  private trait Cursor {
    def nextCursor : Future[Cursor]
  }

  private object HeadOfFeed {

    /** Builds an [[Cursor]] for a [[Feed]].
      *
      * @return an [[EntryPointer]] pointing to the head of the [[Feed]] if non-empty,
      * otherwise returns an [[EndOfEntries]]
      */
    def apply(feed: Feed[EntryType], lastEntryRef:Option[EntryRef] = None): Cursor = {

      feed.entries match {
        case head :: tail =>
          EntryPointer(
            currentEntry = feed.entries.head,
            stillToProcessEntries = feed.entries.tail,
            entryRef = EntryRef(feed.resolveUrl(feed.selfLink.href), feed.entries.head.id),
            feed = feed
          )

        case Nil => EndOfEntries(lastEntryRef)
      }

    }
  }

  private case class InitCursor(entryRefOpt: Option[EntryRef] = None) extends Cursor {

    /** Build an EventCursor according to the following rules:
      *
      *  - If there is no `EntryRef` => create a new cursor starting from the head of the Feed.
      *  - If there is a valid `EntryRef` => drop the corresponding entry and create new cursor starting
      *    from the head of the Feed.
      *  - If the last element of this page is already consumed
      *    - If there is 'previous' Link  create an `EntryOnPreviousFeedPage` cursor for the next page of the feed
      *    - If there is no 'previous' Link then create an `EndOfEntries` cursor.
      */
    def nextCursor : Future[Cursor] = {

      entryRefOpt match {
        // if there is a EntryRef, we fetch the page where it is
        // expected to be and we build a cursor with it
        case Some(entryRef) =>
          feedProvider.fetchFeed(entryRef.url.path).flatMap { feed =>
            buildCursor(feed, entryRef)
          }

        // no initial EntryRef? We fetch the page from the beginning
        // and build a cursor staring from the head of the Feed
        case None =>
          feedProvider.fetchFeed().map { feed =>
            HeadOfFeed(feed)
          }
      }

    }


    private def buildCursor(feed: Feed[EntryType], entryRef: EntryRef): Future[Cursor] = {

      val transformedFeed =
        if(feed.entries.exists(_.id == entryRef.entryId)) {
          // drop entry corresponding to this EntryRef
          val remainingEntries = feed.entries.dropWhile(_.id != entryRef.entryId)
          remainingEntries match {
            case x :: xs => feed.copy(entries = xs)
            case Nil => feed.copy(entries = Nil)
          }
        } else {
          throw new EntryNotFoundException(entryRef)
        }

      if (transformedFeed.entries.nonEmpty) {
        // go for a new EntryPointer if it still have entries
        Future.successful(HeadOfFeed(transformedFeed))
      } else {
        // Is its the end of this Feed?
        // decide where to go: EndOfEntries or EntryOnPreviousFeedPage?
        feed.previousLink match {
          case Some(previousLink) => EntryOnPreviousFeedPage(feed.resolveUrl(previousLink.href), entryRef).nextCursor
          case None => Future.successful(EndOfEntries(Some(entryRef)))
        }
      }

    }
  }


  private case class EntryPointer(currentEntry: Entry[EntryType],
                                  stillToProcessEntries: Entries,
                                  entryRef: EntryRef,
                                  feed: Feed[EntryType]) extends Cursor {

    /** The next [[Cursor]]
      *   - The next [[EntryPointer]] if it still have entries to be consumed.
      *   - An [[EndOfEntries]] in case current page is exhausted and no new page is available.
      */
    def nextCursor : Future[Cursor] = {

      if (stillToProcessEntries.nonEmpty) {
        val nextEntryId = stillToProcessEntries.head.id
        Future.successful {
          copy(
            currentEntry = stillToProcessEntries.head,
            stillToProcessEntries = stillToProcessEntries.tail, // moving forward, dropping head
            entryRef = entryRef.copy(entryId = nextEntryId) // moving position forward
          )
        }
      } else {
        feed.previousLink match {
          case None => Future.successful(EndOfEntries(Some(entryRef)))
          case Some(link) => EntryOnPreviousFeedPage(feed.resolveUrl(link.href), this.entryRef).nextCursor
        }
      }
    }
  }





  private case class EntryOnPreviousFeedPage(previousFeedUrl: Url, lastEntryRef:EntryRef) extends Cursor {

    /** The next [[Cursor]].
      *
      * This method will load the previous page an return an [[EntryPointer]]
      * placed on the head element of this new page.
      */
    def nextCursor : Future[Cursor] = {
      feedProvider.fetchFeed(previousFeedUrl.path).map {
        feed => HeadOfFeed(feed, Some(lastEntryRef))
      }
    }
  }



  private case class EndOfEntries(lastEntryRef: Option[EntryRef]) extends Cursor {
    /** Throws a NonSuchElementException since there is no nextCursor for a [[EndOfEntries]] */
    def nextCursor : Future[Cursor] = throw new NoSuchElementException("No new entries available!")
  }





}

object AsyncFeedEntryIterator {
  object Implicits {
    implicit class AsyncIteratorBuilder[T](feedProvider: AsyncFeedProvider[T]) {
      def iterator(timeout:Duration)(implicit ec: ExecutionContext) = new AsyncFeedEntryIterator(feedProvider, timeout)
    }
  }
}