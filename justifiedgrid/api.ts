import type { GalleryItem, PexelsCuratedResponse } from './types';

const CURATED_ENDPOINT = 'https://api.pexels.com/v1/curated?per_page=20';

export async function fetchCuratedPhotos(apiKey: string): Promise<GalleryItem[]> {
  const response = await fetch(CURATED_ENDPOINT, {
    headers: {
      Authorization: apiKey,
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error('Der Pexels API-Key ist ungueltig oder fehlt.');
    }

    throw new Error(`Pexels konnte nicht geladen werden (${response.status}).`);
  }

  const data = await response.json() as PexelsCuratedResponse;

  return data.photos.slice(0, 20).map((photo) => ({
    id: photo.id,
    width: photo.width,
    height: photo.height,
    aspectRatio: photo.width / photo.height,
    alt: photo.alt || `Foto von ${photo.photographer}`,
    imageUrl: photo.src.large2x || photo.src.large || photo.src.medium,
    photographer: photo.photographer,
    photographerUrl: photo.photographer_url,
    pageUrl: photo.url,
  }));
}
