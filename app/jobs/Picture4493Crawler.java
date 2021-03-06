package jobs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import models.Album;
import models.Picture;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import play.Logger;
import play.db.jpa.JPA;
import play.jobs.Every;
import play.jobs.Job;
import play.libs.Codec;
import play.mvc.Router;
import play.vfs.VirtualFile;
import utils.BaseX;
import utils.UpYunUtils;

/**
 * User: divxer Date: 12-6-4 Time: 上午12:17
 */
@Every("7h")
// @OnApplicationStart(async=true)
public class Picture4493Crawler extends Job {
  public static final String domainName = "http://www.4493.com";

  public void doJob() {
    try {
      Document doc = Jsoup.connect(domainName).timeout(10000).get();

      // 获取文章列表
      Elements listPage = doc.select("html body div.allbox div.nav_menu ul li a");

      for (Element element : listPage) {
        if (!element.attr("abs:href").contentEquals("http://www.4493.com")
            && !element.attr("abs:href").contentEquals("http://www.4493.com/")) {
          Logger.info("URL " + element.attr("abs:href") + " will be fetched now!");

          processChannel(element.attr("abs:href"));
        }
      }
    } catch (IOException e) {
      Logger.error(e, "error in fetch front page.");
    }
  }

  private static void processChannel(String channelUrl) {
    try {
      Document doc = Jsoup.connect(channelUrl).timeout(10000).get();

      // 获取文章列表
      Elements listPage = doc.select("html body div.allbox div.category_list div.page div.page1 a");

      for (Element element : listPage) {
        String pageUrl = element.attr("abs:href");
        if (pageUrl == null || pageUrl.length() == 0) {
          Logger.error("page url is null!");
        } else {
          Logger.info("process page url:" + pageUrl);
          processPage(pageUrl);
        }
      }
    } catch (IOException e) {
      Logger.error(e, "error in fetch page.");
    }
  }

  private static void processPage(String pageUrl) {
    try {
      Document doc = Jsoup.connect(pageUrl).timeout(10000).get();
      Elements articleList = doc.select("html body div.allbox div.category_list ul.img_120_160 li");

      for (Element article : articleList) {
        Element articleLink = article.children().last();
        String articleUrl = article.children().first().attr("abs:href");
        String articleTitle = articleLink.text();
        // 新专辑，开始抓取
        if (Album.find("bySource", articleUrl).fetch().size() == 0) {
          Logger.info("fetch album: " + articleTitle + " url: " + articleUrl);
          processArticle(articleUrl, articleTitle);
        }
      }

    } catch (IOException e) {
      Logger.error(e, "error in process page.");
    }
  }

  private static void processArticle(String articleUrl, String articleTitle) {
    try {
      Document doc = Jsoup.connect(articleUrl).timeout(10000).get();

      Album album;
      if (Album.find("bySource", articleUrl).fetch().size() == 0) {
        album = new Album(articleTitle, "", "", articleUrl, null);
      } else {
        album = Album.find("bySource", articleUrl).first();
      }

      Elements elements =
          doc.select("html body div.allbox div.pic_content div.pic_show div.page1 a");
      elements.remove(elements.size() - 1);
      for (Element element : elements) {
        String imgUrl = element.attr("abs:href");
        if (!StringUtils.isEmpty(imgUrl)) {
          Logger.info("get img url from url: " + imgUrl);
          getImages(album, imgUrl, articleTitle, "");
        } else {
          Logger.error("picture url is empty in page url: " + articleUrl);
        }
      }
    } catch (IOException e) {
      Logger.error(e, "error in process article.");
    }
  }

  public static void getImages(Album album, String url, String articleTitle,
      String articleDescription) {
    try {
      Document doc = Jsoup.connect(url).timeout(10000).get();

      Element imgElement =
          doc.select(
              "html body div.allbox div.pic_content div.pic_show div#baidu900 span#txt a img")
              .last();
      if (imgElement == null) {
        return;
      }
      String sourceUrl = imgElement.attr("abs:src");
      if (Picture.find("bySource", sourceUrl).fetch().size() != 0) {
        Logger.info("picture url: " + sourceUrl + " is exist!");
        return;
      }

      String dagefenUrl = fetch2Dagefen(sourceUrl);
      if (dagefenUrl != null && album.dagefenUrl == null) {
        album.dagefenUrl = dagefenUrl;
      }
      // 把图片上传至imgur
      // String imgUrl = ImgurUtils.uploadImgs2Imgur(sourceUrl);
      String imgUrl = null;

      // 保存数据到数据库
      if (imgUrl != null || dagefenUrl != null) {
        save(album, sourceUrl, articleTitle, articleDescription, imgUrl, dagefenUrl);
      }
    } catch (IOException e) {
      Logger.error(e, "error in get image.");
    }
  }

  private static String fetch2Dagefen(String sourceUrl) throws IOException {
    DefaultHttpClient httpclient = new DefaultHttpClient();
    httpclient.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 15000);
    HttpGet get = new HttpGet(sourceUrl);
    HttpResponse response = httpclient.execute(get);

    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
      Logger.error("error. sourceUrl: " + sourceUrl + ". http status:"
          + response.getStatusLine().getStatusCode());
      return null;
    }

    HttpEntity entity = response.getEntity();
    InputStream in = entity.getContent();
    String fileName = sourceUrl.substring(sourceUrl.lastIndexOf("/") + 1);

    File downloadDir =
        new File(System.getProperty("java.io.tmpdir") + "mmonlyPics" + File.separator);
    boolean result = downloadDir.mkdirs();

    String pictureLocation =
        VirtualFile.fromRelativePath("/upload/").getRealFile().getAbsolutePath() + File.separator
            + "mmonlyPics" + File.separator + Codec.UUID() + "_" + fileName;

    File _f =
        new File(VirtualFile.fromRelativePath("/upload/").getRealFile().getAbsolutePath()
            + File.separator + "mmonlyPics");
    if (!_f.exists()) {
      boolean t = _f.mkdirs();
      if (!t) return null;
    }

    FileOutputStream output = new FileOutputStream(pictureLocation);

    int chunkSize = 1024 * 8;
    byte[] buf = new byte[chunkSize];
    int readLen;
    while ((readLen = in.read(buf, 0, buf.length)) != -1) {
      output.write(buf, 0, readLen);
    }
    in.close();
    output.close();

    EntityUtils.consume(entity);

    httpclient.getConnectionManager().shutdown();

    File file = new File(pictureLocation);

    return Router.reverse(VirtualFile.open(file), true);
  }

  private static void downloadImage(Album album, String imgUrl, String dirName,
      String articleDescription) throws IOException {


    DefaultHttpClient httpclient = new DefaultHttpClient();
    httpclient.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 15000);
    HttpGet get = new HttpGet(imgUrl);
    HttpResponse response = httpclient.execute(get);

    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
      Logger.error("error. imgUrl: " + imgUrl + ". http status:"
          + response.getStatusLine().getStatusCode());
      return;
    }

    HttpEntity entity = response.getEntity();
    InputStream in = entity.getContent();
    String fileName = imgUrl.substring(imgUrl.lastIndexOf("/") + 1);

    File downloadDir =
        new File(System.getProperty("java.io.tmpdir") + "mmonlyPics" + File.separator + dirName
            + File.separator);
    boolean result = downloadDir.mkdirs();

    String pictureLocation =
        System.getProperty("java.io.tmpdir") + "mmonlyPics" + File.separator + dirName
            + File.separator + fileName;

    FileOutputStream output = new FileOutputStream(pictureLocation);

    int chunkSize = 1024 * 8;
    byte[] buf = new byte[chunkSize];
    int readLen;
    while ((readLen = in.read(buf, 0, buf.length)) != -1) {
      output.write(buf, 0, readLen);
    }
    in.close();
    output.close();

    EntityUtils.consume(entity);

    httpclient.getConnectionManager().shutdown();

    File file = new File(pictureLocation);

    String upYunDirName = getTinyUrl(album.id.toString());
    String fileUrl = UpYunUtils.picBedDomain + "/" + upYunDirName + "/" + file.getName();

    save(album, imgUrl, dirName, articleDescription, fileUrl, "");


    // 删除临时图片
    if (file.exists()) {
      boolean deleteResult = file.delete();
      if (deleteResult) {
        Logger.error("file: " + fileName + " delete failed!");
      }
    }
  }

  private static void save(Album album, String sourceUrl, String title, String picDescription,
      String imgUrl, String dagefenUrl) {
    // 保存图片
    Picture picture = new Picture(title, picDescription, "", sourceUrl, album, imgUrl);

    picture.dagefenUrl = dagefenUrl;

    // 保存图片专辑
    album.pictures.add(picture);
    Album savedAlbum = album.save();

    // 保存图片地址
    picture.imgUrl = imgUrl;

    // 保存专辑缩略图地址
    if (album.thumbnail == null) {
      savedAlbum.thumbnail = imgUrl;
      savedAlbum.save();
    }

    // 事务提交，并开启新事务
    JPA.em().getTransaction().commit();
    JPA.em().getTransaction().begin();
    // JPA.em().flush();
    // JPA.em().clear();
  }

  private static String getTinyUrl(String pictureId) {
    BaseX bx = new BaseX(BaseX.DICTIONARY_32_SMALL);
    String encoded = bx.encode(new BigInteger(pictureId));

    return encoded;
  }
}
